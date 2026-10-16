/*
 * Copyright 2026 Kingsrook, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.backups.sla;


import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.alerts.BackupAlerts;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qqq.backend.core.cluster.ClusterCaches;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The SLA checker (design spec section 10.2): backupCheckSla.  A scheduled process
 ** that is independent of the backup trigger, so a broken trigger cannot also
 ** silence the check.  For each active plan it computes slaStatus:
 ** <ul>
 **    <li>MISSING_IN_STORAGE: the catalog says success but the manifest is gone
 **    (a head on manifestKey, a positive answer cached for an hour);</li>
 **    <li>FAILING: the last two finished runs failed;</li>
 **    <li>ABANDONED: a RUNNING run has a heartbeat older than ten minutes (the run
 **    is marked ABANDONED);</li>
 **    <li>LATE: no success within slaHours;</li>
 **    <li>OWNER_INVALID: the plan owner no longer validates (the configured
 **    planOwnerValidator);</li>
 **    <li>UNVERIFIED: no passed CONTENT or TEST_RESTORE verification within
 **    verifySlaDays;</li>
 **    <li>OK otherwise (UNKNOWN for a new plan that has not had time to run yet).</li>
 ** </ul>
 ** When several apply, the status shown is the first in that order; the alerts are
 ** independent, one per condition.  Once a day it also lists each plan's prefix in
 ** the bucket and reports orphans (run prefixes that are not in the catalog) and
 ** debris (prefixes with no manifest older than 24 hours): look at the bucket, not
 ** at the job.
 *******************************************************************************/
public class SlaChecker
{
   private static final QLogger LOG = QLogger.getLogger(SlaChecker.class);

   public static final String OK                 = "OK";
   public static final String LATE               = "LATE";
   public static final String FAILING            = "FAILING";
   public static final String MISSING_IN_STORAGE = "MISSING_IN_STORAGE";
   public static final String UNVERIFIED         = "UNVERIFIED";
   public static final String ABANDONED          = "ABANDONED";
   public static final String OWNER_INVALID      = "OWNER_INVALID";
   public static final String UNKNOWN            = "UNKNOWN";

   /** A RUNNING run whose heartbeat is older than this is abandoned. */
   public static final Duration STALE_HEARTBEAT = Duration.ofMinutes(10);

   /** How long a successful manifest head is trusted. */
   public static final Duration MANIFEST_CACHE = Duration.ofHours(1);

   /** How often the bucket is listed for orphans and debris. */
   public static final Duration BUCKET_AUDIT_INTERVAL = Duration.ofHours(24);

   /** A prefix with no manifest older than this is debris. */
   public static final Duration DEBRIS_AGE = Duration.ofHours(24);

   private static final Set<String> FAILED_STATUSES   = Set.of("FAILED", "TIMED_OUT", "ABANDONED");
   private static final Set<String> FINISHED_STATUSES = Set.of("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "FAILED", "TIMED_OUT", "ABANDONED");

   private static final Map<String, Instant> MANIFEST_OK_AT = ClusterCaches.nodeLocal(new ConcurrentHashMap<>(),
      "throttles this node's storage HEAD of a manifest to once per MANIFEST_CACHE; it caches storage state, not editable data, "
         + "and a pruned run is skipped by its prunedDate, so each node is as fresh as a single node");
   private static final Map<String, Instant> LAST_AUDIT_AT  = ClusterCaches.nodeLocal(new ConcurrentHashMap<>(),
      "throttles this node's bucket audit to once per BUCKET_AUDIT_INTERVAL; another node auditing again is only repeated work");

   private final BackupCatalog catalog = new BackupCatalog();



   /***************************************************************************
    ** The outcome of checking one plan.
    **
    ** @param planId the plan id
    ** @param planKey the plan key
    ** @param previousStatus slaStatus before the check
    ** @param status slaStatus now
    ** @param abandonedRuns runs marked ABANDONED by this check
    ** @param orphans orphan run prefixes found by the bucket audit (-1 when the audit did not run)
    ** @param debris debris prefixes found by the bucket audit (-1 when the audit did not run)
    ***************************************************************************/
   public record PlanResult(Integer planId, String planKey, String previousStatus, String status, int abandonedRuns, int orphans, int debris)
   {
   }



   /*******************************************************************************
    ** Forget the cached manifest heads and bucket audit times (tests).
    *******************************************************************************/
   public static void resetCaches()
   {
      MANIFEST_OK_AT.clear();
      LAST_AUDIT_AT.clear();
   }



   /*******************************************************************************
    ** Check every active plan.
    *******************************************************************************/
   public List<PlanResult> checkAll() throws QException
   {
      List<PlanResult> results = new ArrayList<>();
      for(QRecord plan : catalog.query(BackupsTables.PLAN, new QQueryFilter(new QFilterCriteria("isActive", QCriteriaOperator.EQUALS, true))))
      {
         try
         {
            results.add(check(plan));
         }
         catch(Exception e)
         {
            LOG.warn("The SLA check of a plan failed", e, logPair("plan", plan.getValueString("planKey")));
         }
      }
      return (results);
   }



   /*******************************************************************************
    ** Check one plan: mark stale runs, compute the status, update the plan, raise and
    ** resolve alerts, and (once a day) audit its prefix in the bucket.
    *******************************************************************************/
   public PlanResult check(QRecord plan) throws QException
   {
      Instant now     = BackupsRuntime.getClock().instant();
      Integer planId  = plan.getValueInteger("id");
      String  planKey = plan.getValueString("planKey");
      boolean critical = Boolean.TRUE.equals(plan.getValueBoolean("critical"));

      List<QRecord> runs = catalog.where(BackupsTables.RUN, "backupPlanId", planId);

      /////////////////////////////////////////////////////////////////////
      // a RUNNING run that stopped reporting is abandoned (pod died etc) //
      /////////////////////////////////////////////////////////////////////
      int abandonedNow = 0;
      for(QRecord run : runs)
      {
         if("RUNNING".equals(run.getValueString("status")))
         {
            Instant lastSeen = run.getValueInstant("heartbeatDate") != null ? run.getValueInstant("heartbeatDate") : run.getValueInstant("startDate");
            if(lastSeen != null && lastSeen.isBefore(now.minus(STALE_HEARTBEAT)))
            {
               Map<String, Serializable> update = new LinkedHashMap<>();
               update.put("status", "ABANDONED");
               update.put("endDate", now);
               update.put("errorMessage", "The run stopped reporting a heartbeat (the process or pod probably died); resume it or run the plan again");
               catalog.update(BackupsTables.RUN, run.getValueInteger("id"), update);
               abandonedNow++;
            }
         }
      }
      if(abandonedNow > 0)
      {
         runs = catalog.where(BackupsTables.RUN, "backupPlanId", planId);
      }

      List<QRecord> finished = runs.stream().filter(r -> FINISHED_STATUSES.contains(r.getValueString("status"))).toList();
      boolean latestFailed   = !finished.isEmpty() && FAILED_STATUSES.contains(finished.get(0).getValueString("status"));
      boolean failing        = finished.size() >= 2 && latestFailed && FAILED_STATUSES.contains(finished.get(1).getValueString("status"));
      boolean abandoned      = abandonedNow > 0 || (!finished.isEmpty() && "ABANDONED".equals(finished.get(0).getValueString("status")));

      boolean missing    = isManifestMissing(plan, now);
      boolean late       = isLate(plan, now);
      boolean ownerBad   = isOwnerInvalid(plan);
      boolean unverified = isUnverified(plan, now);

      String status;
      if(missing)
      {
         status = MISSING_IN_STORAGE;
      }
      else if(failing)
      {
         status = FAILING;
      }
      else if(abandoned)
      {
         status = ABANDONED;
      }
      else if(late)
      {
         status = LATE;
      }
      else if(ownerBad)
      {
         status = OWNER_INVALID;
      }
      else if(unverified)
      {
         status = UNVERIFIED;
      }
      else if(plan.getValueInstant("lastSuccessDate") == null)
      {
         status = UNKNOWN;
      }
      else
      {
         status = OK;
      }

      String previous = plan.getValueString("slaStatus");
      if(!status.equals(previous))
      {
         catalog.update(BackupsTables.PLAN, planId, Map.of("slaStatus", status));
      }

      signal(plan, planKey, critical, missing, late, ownerBad, unverified, latestFailed, failing, abandoned, finished.isEmpty() ? null : finished.get(0));

      int[] audit = auditBucket(plan, planKey, runs, now);
      return (new PlanResult(planId, planKey, previous, status, abandonedNow, audit[0], audit[1]));
   }



   /*******************************************************************************
    ** Raise and resolve the plan's alerts, one per condition.
    *******************************************************************************/
   private void signal(QRecord plan, String planKey, boolean critical, boolean missing, boolean late, boolean ownerBad, boolean unverified,
                       boolean latestFailed, boolean failing, boolean abandoned, QRecord latestFinished)
   {
      String name = plan.getValueString("name") == null ? planKey : plan.getValueString("name");

      if(missing)
      {
         BackupAlerts.raise(BackupAlerts.RUN_MISSING, planKey, BackupAlertSink.Severity.CRITICAL, "The latest backup of \"" + name + "\" is missing from storage",
            "plan", planKey, "planName", name);
      }
      else
      {
         BackupAlerts.resolve(BackupAlerts.RUN_MISSING, planKey);
      }

      if(late)
      {
         BackupAlerts.raise(BackupAlerts.PLAN_MISSED, planKey, critical ? BackupAlertSink.Severity.CRITICAL : BackupAlertSink.Severity.ERROR,
            "Backup \"" + name + "\" did not run in time", "plan", planKey, "planName", name, "slaHours", String.valueOf(slaHours(plan)));
      }
      else
      {
         BackupAlerts.resolve(BackupAlerts.PLAN_MISSED, planKey);
      }

      if(latestFailed || abandoned)
      {
         BackupAlerts.raise(BackupAlerts.RUN_FAILED, planKey, failing ? BackupAlertSink.Severity.ERROR : BackupAlertSink.Severity.WARNING,
            failing ? "Backup \"" + name + "\" has failed twice in a row" : "Backup \"" + name + "\" run failed", "plan", planKey, "planName", name);
      }
      else if(latestFinished != null)
      {
         BackupAlerts.resolve(BackupAlerts.RUN_FAILED, planKey);
      }

      if(ownerBad)
      {
         BackupAlerts.raise(BackupAlerts.PLAN_OWNER_INVALID, planKey, BackupAlertSink.Severity.WARNING, "The owner of backup plan \"" + name + "\" is not valid",
            "plan", planKey, "planName", name);
      }
      else
      {
         BackupAlerts.resolve(BackupAlerts.PLAN_OWNER_INVALID, planKey);
      }

      if(unverified)
      {
         BackupAlerts.raise(BackupAlerts.VERIFY_OVERDUE, planKey, BackupAlertSink.Severity.WARNING, "No backup of \"" + name + "\" has been verified recently",
            "plan", planKey, "planName", name, "verifySlaDays", String.valueOf(plan.getValueInteger("verifySlaDays")));
      }
      else
      {
         BackupAlerts.resolve(BackupAlerts.VERIFY_OVERDUE, planKey);
      }
   }



   /*******************************************************************************
    ** The catalog says the last success exists; is its manifest still in the bucket?
    ** A positive answer is trusted for an hour; a negative one is always re-checked.
    ** A storage that cannot be reached does not mean the manifest is gone.
    *******************************************************************************/
   private boolean isManifestMissing(QRecord plan, Instant now) throws QException
   {
      Integer successId = plan.getValueInteger("lastSuccessRunId");
      if(successId == null)
      {
         return (false);
      }
      QRecord run = catalog.get(BackupsTables.RUN, successId).orElse(null);
      if(run == null || run.getValueString("manifestKey") == null || run.getValueString("storageBucketAlias") == null || run.getValueInstant("prunedDate") != null)
      {
         return (false);
      }

      String  cacheKey = run.getValueString("storageBucketAlias") + "|" + run.getValueString("manifestKey");
      Instant okAt     = MANIFEST_OK_AT.get(cacheKey);
      if(okAt != null && okAt.plus(MANIFEST_CACHE).isAfter(now))
      {
         return (false);
      }

      try
      {
         BackupStorage storage = BackupsRuntime.storageFor(run.getValueString("storageBucketAlias"));
         if(storage.head(run.getValueString("manifestKey")).isPresent())
         {
            MANIFEST_OK_AT.put(cacheKey, now);
            return (false);
         }
         MANIFEST_OK_AT.remove(cacheKey);
         return (true);
      }
      catch(RuntimeException e)
      {
         LOG.warn("Could not check the manifest of the last backup", e, logPair("plan", plan.getValueString("planKey")));
         return (false);
      }
   }



   /*******************************************************************************
    ** No success within slaHours.  A manual-only plan (no cron) with no slaHours has
    ** no SLA.  A new plan gets slaHours to run its first backup.
    *******************************************************************************/
   private boolean isLate(QRecord plan, Instant now)
   {
      Integer hours = slaHours(plan);
      if(hours == null)
      {
         return (false);
      }
      Instant lastSuccess = plan.getValueInstant("lastSuccessDate");
      Instant reference   = lastSuccess != null ? lastSuccess : plan.getValueInstant("createDate");
      return (reference != null && reference.plus(Duration.ofHours(hours)).isBefore(now));
   }



   /*******************************************************************************
    ** slaHours of a plan: its value, else 26 for a scheduled plan, else none.
    *******************************************************************************/
   private Integer slaHours(QRecord plan)
   {
      if(plan.getValueInteger("slaHours") != null)
      {
         return (plan.getValueInteger("slaHours"));
      }
      String cron = plan.getValueString("cronExpression");
      return (cron != null && !cron.isBlank() ? 26 : null);
   }



   /*******************************************************************************
    ** The plan has a successful backup but no passed CONTENT or TEST_RESTORE
    ** verification within verifySlaDays.
    *******************************************************************************/
   private boolean isUnverified(QRecord plan, Instant now)
   {
      Integer days = plan.getValueInteger("verifySlaDays");
      if(days == null || plan.getValueInstant("lastSuccessDate") == null)
      {
         return (false);
      }
      Instant reference = plan.getValueInstant("lastVerifiedDate") != null ? plan.getValueInstant("lastVerifiedDate") : plan.getValueInstant("createDate");
      return (reference != null && reference.plus(Duration.ofDays(days)).isBefore(now));
   }



   /*******************************************************************************
    ** The plan owner no longer validates.
    *******************************************************************************/
   private boolean isOwnerInvalid(QRecord plan)
   {
      BackupsQBitConfig config = BackupsRuntime.getConfig();
      String            owner  = plan.getValueString("ownerUserId");
      if(config == null || config.getPlanOwnerValidator() == null || owner == null || owner.isBlank())
      {
         return (false);
      }
      try
      {
         return (!config.getPlanOwnerValidator().test(owner));
      }
      catch(RuntimeException e)
      {
         LOG.warn("The plan owner validator failed", e, logPair("plan", plan.getValueString("planKey")));
         return (false);
      }
   }



   /*******************************************************************************
    ** Once a day, list the plan's prefix in the bucket: orphans (run prefixes not in
    ** the catalog) and debris (no manifest, older than 24 hours).  Returns
    * {orphans, debris}, or {-1, -1} when it did not run.
    *******************************************************************************/
   private int[] auditBucket(QRecord plan, String planKey, List<QRecord> runs, Instant now)
   {
      String alias = plan.getValueString("storageBucketAlias");
      if(alias == null)
      {
         return (new int[] { -1, -1 });
      }
      String  auditKey = alias + "|" + planKey;
      Instant last     = LAST_AUDIT_AT.get(auditKey);
      if(last != null && last.plus(BUCKET_AUDIT_INTERVAL).isAfter(now))
      {
         return (new int[] { -1, -1 });
      }

      try
      {
         BackupStorage storage    = BackupsRuntime.storageFor(alias);
         String        planPrefix = planPrefix(plan, planKey);

         Set<String> known  = new HashSet<>();
         Set<String> active = new HashSet<>();
         for(QRecord run : runs)
         {
            known.add(run.getValueString("runPrefix"));
            if("RUNNING".equals(run.getValueString("status")) || "QUEUED".equals(run.getValueString("status")))
            {
               active.add(run.getValueString("runPrefix"));
            }
         }

         Map<String, Boolean> hasManifest = new LinkedHashMap<>();
         Map<String, Instant> newest      = new LinkedHashMap<>();
         for(StoredObjectInfo object : storage.list(planPrefix))
         {
            String[] parts = object.key().substring(planPrefix.length()).split("/");
            if(parts.length < 5)
            {
               continue;
            }
            String runPrefix = planPrefix + parts[0] + "/" + parts[1] + "/" + parts[2] + "/" + parts[3] + "/";
            hasManifest.merge(runPrefix, "manifest.json".equals(parts[4]), Boolean::logicalOr);
            if(object.lastModified() != null)
            {
               newest.merge(runPrefix, object.lastModified(), (a, b) -> a.isAfter(b) ? a : b);
            }
         }

         int orphans = 0;
         int debris  = 0;
         for(Map.Entry<String, Boolean> entry : hasManifest.entrySet())
         {
            String runPrefix = entry.getKey();
            if(active.contains(runPrefix))
            {
               continue;
            }
            if(!known.contains(runPrefix))
            {
               orphans++;
            }
            Instant modified = newest.get(runPrefix);
            if(!entry.getValue() && modified != null && modified.plus(DEBRIS_AGE).isBefore(now))
            {
               debris++;
            }
         }

         LAST_AUDIT_AT.put(auditKey, now);
         if(orphans + debris > 0)
         {
            BackupAlerts.raise(BackupAlerts.STORAGE_DEBRIS, planKey, BackupAlertSink.Severity.INFO, "Backup storage of \"" + planKey + "\" has orphans or debris",
               "plan", planKey, "orphans", String.valueOf(orphans), "debris", String.valueOf(debris));
         }
         else
         {
            BackupAlerts.resolve(BackupAlerts.STORAGE_DEBRIS, planKey);
         }
         return (new int[] { orphans, debris });
      }
      catch(RuntimeException e)
      {
         LOG.warn("The bucket audit failed", e, logPair("plan", planKey));
         return (new int[] { -1, -1 });
      }
   }



   /*******************************************************************************
    ** The prefix of a plan's runs in its bucket, the same way the backup engine
    ** builds it: (plan prefix)/(instance name)/(planKey)/.
    *******************************************************************************/
   static String planPrefix(QRecord plan, String planKey)
   {
      BackupsQBitConfig config   = BackupsRuntime.getConfig();
      String            instance = config != null && config.getInstanceName() != null ? config.getInstanceName() : "qqq";
      String            prefix   = plan.getValueString("prefix") != null ? plan.getValueString("prefix") : "qqq-backups";
      while(prefix.startsWith("/"))
      {
         prefix = prefix.substring(1);
      }
      while(prefix.endsWith("/"))
      {
         prefix = prefix.substring(0, prefix.length() - 1);
      }
      return (prefix + "/" + instance + "/" + planKey + "/");
   }
}
