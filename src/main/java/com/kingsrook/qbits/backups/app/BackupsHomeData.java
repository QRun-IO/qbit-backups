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

package com.kingsrook.qbits.backups.app;


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The data behind the Backups home: plan status, what needs attention, recent
 ** activity and the size trend.  Everything is read from the qbit tables; nothing
 ** is invented.  Home cards read the denormalized plan fields (slaStatus, last
 ** success) that the processes and the SLA checker maintain.
 *******************************************************************************/
public class BackupsHomeData
{
   /** Plan statuses that need attention (the plan or its backups have a problem). */
   public static final List<String> ATTENTION = List.of("LATE", "FAILING", "MISSING_IN_STORAGE", "UNVERIFIED", "ABANDONED", "OWNER_INVALID");

   private final List<QRecord> plans;
   private final List<QRecord> recentRuns;
   private final List<QRecord> failedRuns;
   private final List<QRecord> verifications;
   private final List<QRecord> restores;
   private final Instant       now;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   BackupsHomeData(List<QRecord> plans, List<QRecord> recentRuns, List<QRecord> failedRuns, List<QRecord> verifications, List<QRecord> restores, Instant now)
   {
      this.plans = plans;
      this.recentRuns = recentRuns;
      this.failedRuns = failedRuns;
      this.verifications = verifications;
      this.restores = restores;
      this.now = now;
   }



   /*******************************************************************************
    ** Load from the tables.
    *******************************************************************************/
   public static BackupsHomeData load() throws QException
   {
      BackupCatalog catalog = new BackupCatalog();
      Instant       now     = BackupsRuntime.getClock().instant();
      List<QRecord> plans   = catalog.query(BackupsTables.PLAN, new QQueryFilter().withOrderBy(new QFilterOrderBy("name", true)));

      QQueryFilter recent = new QQueryFilter().withOrderBy(new QFilterOrderBy("id", false));
      recent.setLimit(200);
      List<QRecord> runs = catalog.query(BackupsTables.RUN, recent);
      List<QRecord> failed = new ArrayList<>();
      Instant weekAgo = now.minus(Duration.ofDays(7));
      for(QRecord run : runs)
      {
         Instant start = run.getValueInstant("startDate");
         if(List.of("FAILED", "TIMED_OUT", "ABANDONED").contains(run.getValueString("status")) && (start == null || start.isAfter(weekAgo)))
         {
            failed.add(run);
         }
      }
      QQueryFilter verificationFilter = new QQueryFilter().withOrderBy(new QFilterOrderBy("id", false));
      verificationFilter.setLimit(100);
      QQueryFilter restoreFilter = new QQueryFilter().withOrderBy(new QFilterOrderBy("id", false));
      restoreFilter.setLimit(20);
      return (new BackupsHomeData(plans, runs, failed, catalog.query(BackupsTables.VERIFICATION, verificationFilter),
         catalog.query(BackupsTables.RESTORE_RUN, restoreFilter), now));
   }



   /*******************************************************************************
    ** Plans.
    *******************************************************************************/
   public List<QRecord> plans()
   {
      return (plans);
   }



   /*******************************************************************************
    ** Recent runs (newest first).
    *******************************************************************************/
   public List<QRecord> recentRuns()
   {
      return (recentRuns);
   }



   /*******************************************************************************
    ** Runs that failed in the last 7 days.
    *******************************************************************************/
   public List<QRecord> failedRuns()
   {
      return (failedRuns);
   }



   /*******************************************************************************
    ** Verifications (newest first).
    *******************************************************************************/
   public List<QRecord> verifications()
   {
      return (verifications);
   }



   /*******************************************************************************
    ** Restores (newest first).
    *******************************************************************************/
   public List<QRecord> restores()
   {
      return (restores);
   }



   /*******************************************************************************
    ** The time the data was loaded.
    *******************************************************************************/
   public Instant now()
   {
      return (now);
   }



   /*******************************************************************************
    ** The status to show for a plan: the stored slaStatus when it is a problem state,
    ** otherwise LATE when the age of the last success is past the SLA (the SLA
    ** checker may not have run yet), otherwise the stored status (UNKNOWN when none).
    *******************************************************************************/
   public String effectiveStatus(QRecord plan)
   {
      String stored = plan.getValueString("slaStatus");
      if(stored != null && ATTENTION.contains(stored))
      {
         return (stored);
      }
      Long    age = ageHours(plan);
      Integer sla = plan.getValueInteger("slaHours");
      if(age != null && sla != null && age > sla)
      {
         return ("LATE");
      }
      return (stored == null ? "UNKNOWN" : stored);
   }



   /*******************************************************************************
    ** Plans that need attention.
    *******************************************************************************/
   public List<QRecord> plansNeedingAttention()
   {
      return (plans.stream().filter(p -> Boolean.TRUE.equals(p.getValueBoolean("isActive")) && ATTENTION.contains(effectiveStatus(p))).toList());
   }



   /*******************************************************************************
    ** The last run of a plan (from the recent runs), or null.
    *******************************************************************************/
   public QRecord lastRun(QRecord plan)
   {
      Integer lastRunId = plan.getValueInteger("lastRunId");
      for(QRecord run : recentRuns)
      {
         if(lastRunId != null && lastRunId.equals(run.getValueInteger("id")))
         {
            return (run);
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** The overall state: NO_PLANS, ATTENTION or OK.
    *******************************************************************************/
   public String overallState()
   {
      if(plans.isEmpty())
      {
         return ("NO_PLANS");
      }
      return (plansNeedingAttention().isEmpty() && failedRuns.isEmpty() ? "OK" : "ATTENTION");
   }



   /*******************************************************************************
    ** Hours since the last successful backup of a plan, or null when it has none.
    *******************************************************************************/
   public Long ageHours(QRecord plan)
   {
      Instant last = plan.getValueInstant("lastSuccessDate");
      return (last == null ? null : Duration.between(last, now).toHours());
   }



   /*******************************************************************************
    ** The latest test restore of a plan, or null.
    *******************************************************************************/
   public QRecord latestTestRestore(Integer planId)
   {
      for(QRecord verification : verifications)
      {
         if("TEST_RESTORE".equals(verification.getValueString("level")))
         {
            Integer runId = verification.getValueInteger("backupRunId");
            for(QRecord run : recentRuns)
            {
               if(runId != null && planId != null && runId.equals(run.getValueInteger("id")) && planId.equals(run.getValueInteger("backupPlanId")))
               {
                  return (verification);
               }
            }
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** Successful runs of a plan, oldest first, for the trend (bytes stored, rows, duration).
    *******************************************************************************/
   public List<QRecord> successfulRuns(Integer planId)
   {
      List<QRecord> rs = new ArrayList<>();
      if(planId == null)
      {
         return (rs);
      }
      for(QRecord run : recentRuns)
      {
         if(planId.equals(run.getValueInteger("backupPlanId")) && run.getValueString("status") != null && run.getValueString("status").startsWith("SUCCEEDED"))
         {
            rs.add(0, run);
         }
      }
      return (rs);
   }



   /*******************************************************************************
    ** Runs kept per retention class for a plan (as classified by the last prune).
    *******************************************************************************/
   public Map<String, Integer> retentionCounts(Integer planId)
   {
      Map<String, Integer> counts = new LinkedHashMap<>();
      if(planId == null)
      {
         return (counts);
      }
      for(QRecord run : recentRuns)
      {
         if(planId.equals(run.getValueInteger("backupPlanId")) && run.getValueString("status") != null && run.getValueString("status").startsWith("SUCCEEDED")
            && run.getValue("prunedDate") == null)
         {
            counts.merge(run.getValueString("retentionClass") == null ? "UNCLASSIFIED" : run.getValueString("retentionClass"), 1, Integer::sum);
         }
      }
      return (counts);
   }
}
