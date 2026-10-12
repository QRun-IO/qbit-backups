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

package com.kingsrook.qbits.backups.run;


import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Decides whether a run may start, and starts it (spec 9.1): one run per plan at
 ** a time, and at most maxConcurrentRuns across the instance.
 **
 ** The lease is the run row itself.  A run is inserted QUEUED, and flipped to
 ** RUNNING only when no other run of the plan has a live heartbeat (newer than
 ** HEARTBEAT_STALE) and the instance has a free slot.  The check and the flip happen
 ** under one lock in this JVM; because the lock cannot span pods, the flip is
 ** followed by a second look, and when two runs of the same plan are RUNNING the
 ** one with the higher id gives way.  A second trigger while one is running is not
 ** silent: it leaves a CANCELLED run row saying SKIPPED_CONCURRENT and raises a
 ** warning.  When the instance is full the run stays QUEUED and is started, oldest
 ** first, as slots free up (drainQueue runs after every managed run).
 **
 ** The scheduled entry point and the Run backup now action both come through here.
 *******************************************************************************/
public class BackupRunCoordinator
{
   private static final QLogger LOG = QLogger.getLogger(BackupRunCoordinator.class);

   /** A RUNNING run whose heartbeat is older than this is dead (spec 9.1). */
   public static final Duration HEARTBEAT_STALE = Duration.ofMinutes(10);

   public static final String SKIPPED_CONCURRENT = "SKIPPED_CONCURRENT";
   public static final String QUEUED             = "QUEUED";

   private static final Object LEASE_LOCK = new Object();

   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    ** Run a plan.  The result status is the run status, or SKIPPED_CONCURRENT (a
    ** run of the plan is already live) or QUEUED (the instance is full; the run
    ** starts when a slot frees up).
    *******************************************************************************/
   public BackupRunResult runPlan(QRecord plan, String trigger) throws QException
   {
      return (runPlan(plan, trigger, null));
   }



   /*******************************************************************************
    ** Run a plan, letting the caller adjust the request first (swap the row reader,
    ** add a file reference provider, ...).
    *******************************************************************************/
   public BackupRunResult runPlan(QRecord plan, String trigger, java.util.function.Consumer<BackupRunRequest> customizer) throws QException
   {
      BackupRunRequest request = BackupRunFactory.forPlan(plan, trigger);
      if(customizer != null)
      {
         customizer.accept(request);
      }

      String refusal = encryptionRefusal(plan, request);
      if(refusal != null)
      {
         BackupRunResult result = new BackupRunResult();
         result.setStatus("FAILED");
         result.setErrorMessage(refusal);
         return (result);
      }

      Claim claim = claim(plan.getValueInteger("id"), null, trigger, request.getRequestedBy(), true);
      try
      {
         if(claim.outcome != Outcome.STARTED)
         {
            return (claim.toResult());
         }
         return (execute(request, claim.runId));
      }
      finally
      {
         if(claim.outcome == Outcome.STARTED)
         {
            drainQueue();
         }
      }
   }



   /*******************************************************************************
    ** Run an ad-hoc export (no plan).  It counts as a live run for the instance
    ** limit but, being built from records in hand, it is never queued.
    *******************************************************************************/
   public BackupRunResult runAdHoc(BackupRunRequest request) throws QException
   {
      Claim claim = claim(null, null, request.getTrigger(), request.getRequestedBy(), false);
      try
      {
         return (execute(request, claim.runId));
      }
      finally
      {
         drainQueue();
      }
   }



   /*******************************************************************************
    ** Resume a failed run of a plan: the same run carries on.  Refused (with a
    ** message) when it cannot be resumed, or when a run of the plan is live.
    *******************************************************************************/
   public BackupRunResult resume(QRecord run) throws QException
   {
      QRecord plan = run.getValueInteger("backupPlanId") == null ? null : catalog.get(BackupsTables.PLAN, run.getValueInteger("backupPlanId")).orElse(null);
      String  why  = BackupResumePlanner.whyNotResumable(run, plan);
      if(why != null)
      {
         BackupRunResult refused = new BackupRunResult();
         refused.setRunId(run.getValueInteger("id"));
         refused.setStatus("FAILED");
         refused.setErrorMessage(why);
         return (refused);
      }

      BackupRunRequest request = BackupRunFactory.forPlan(plan, "MANUAL");
      Claim claim = claim(plan.getValueInteger("id"), run.getValueInteger("id"), run.getValueString("trigger"), request.getRequestedBy(), false);
      if(claim.outcome != Outcome.STARTED)
      {
         BackupRunResult refused = claim.toResult();
         refused.setRunId(run.getValueInteger("id"));
         refused.setStatus("FAILED");
         refused.setErrorMessage("Cannot resume now: " + claim.message);
         return (refused);
      }
      try
      {
         request.withResume(BackupResumePlanner.plan(run));
         return (execute(request, claim.runId));
      }
      finally
      {
         drainQueue();
      }
   }



   /*******************************************************************************
    ** Start every queued run that now has a slot, oldest first.
    *******************************************************************************/
   public List<BackupRunResult> drainQueue() throws QException
   {
      List<BackupRunResult> results = new ArrayList<>();
      while(true)
      {
         QRecord next = nextStartable();
         if(next == null)
         {
            return (results);
         }
         Integer planId = next.getValueInteger("backupPlanId");
         QRecord plan   = planId == null ? null : catalog.get(BackupsTables.PLAN, planId).orElse(null);
         if(plan == null)
         {
            catalog.update(BackupsTables.RUN, next.getValueInteger("id"), Map.of("status", "CANCELLED", "endDate", now(), "errorMessage", "The plan of this queued run no longer exists."));
            continue;
         }
         BackupRunRequest request = BackupRunFactory.forPlan(plan, next.getValueString("trigger") == null ? "MANUAL" : next.getValueString("trigger"));
         results.add(execute(request, next.getValueInteger("id")));
      }
   }



   /*******************************************************************************
    ** The oldest QUEUED run that may start now (the plan has no live run and the
    ** instance has a free slot); it is claimed (RUNNING) before it is returned.
    *******************************************************************************/
   private QRecord nextStartable() throws QException
   {
      synchronized(LEASE_LOCK)
      {
         List<QRecord> live = catalog.liveRuns(cutoff());
         for(QRecord queued : catalog.queuedRuns())
         {
            if(live.size() >= maxConcurrentRuns())
            {
               return (null);
            }
            Integer planId = queued.getValueInteger("backupPlanId");
            if(planId != null && live.stream().anyMatch(r -> planId.equals(r.getValueInteger("backupPlanId"))))
            {
               continue;
            }
            catalog.update(BackupsTables.RUN, queued.getValueInteger("id"), Map.of("status", "RUNNING", "heartbeatDate", now()));
            return (queued);
         }
         return (null);
      }
   }



   /*******************************************************************************
    ** Run the engine on a claimed run row, then return its result.
    *******************************************************************************/
   private BackupRunResult execute(BackupRunRequest request, Integer runId)
   {
      request.withExistingRunId(runId).withCancelRequested(() -> catalog.isCancelRequested(runId));
      return (new BackupEngine().execute(request));
   }



   /*******************************************************************************
    ** The lease: insert (or reuse) the run row and decide.
    *******************************************************************************/
   private Claim claim(Integer planId, Integer existingRunId, String trigger, String requestedBy, boolean queueIfFull) throws QException
   {
      synchronized(LEASE_LOCK)
      {
         Instant       now  = now();
         List<QRecord> live = catalog.liveRuns(cutoff());

         Integer runId = existingRunId;
         if(runId == null)
         {
            Map<String, Serializable> values = new LinkedHashMap<>();
            values.put("backupPlanId", planId);
            values.put("runKey", "queued-" + UUID.randomUUID().toString().substring(0, 8));
            values.put("trigger", trigger);
            values.put("requestedByUserId", requestedBy);
            values.put("status", QUEUED);
            values.put("startDate", now);
            runId = catalog.insert(BackupsTables.RUN, values);
         }
         final Integer claimedId = runId;

         if(planId != null && live.stream().anyMatch(r -> planId.equals(r.getValueInteger("backupPlanId")) && !claimedId.equals(r.getValueInteger("id"))))
         {
            return (skip(planId, runId, existingRunId == null, "a run of this plan is already running"));
         }

         if(planId != null && live.size() >= maxConcurrentRuns() && queueIfFull)
         {
            LOG.info("A backup run is queued because the instance is at its concurrent run limit", logPair("runId", runId));
            return (new Claim(Outcome.QUEUED, runId, "the instance is running its maximum of " + maxConcurrentRuns() + " backups; this one is queued"));
         }
         if(planId != null && live.size() >= maxConcurrentRuns() && !queueIfFull)
         {
            return (new Claim(Outcome.QUEUED, runId, "the instance is running its maximum of " + maxConcurrentRuns() + " backups"));
         }

         catalog.update(BackupsTables.RUN, runId, Map.of("status", "RUNNING", "heartbeatDate", now));

         /////////////////////////////////////////////////////////////////////////////
         // a second look: another pod may have claimed the same plan in the meantime //
         /////////////////////////////////////////////////////////////////////////////
         if(planId != null)
         {
            for(QRecord other : catalog.liveRuns(cutoff()))
            {
               if(planId.equals(other.getValueInteger("backupPlanId")) && other.getValueInteger("id") < runId && !other.getValueInteger("id").equals(existingRunId))
               {
                  return (skip(planId, runId, existingRunId == null, "a run of this plan started at the same moment"));
               }
            }
         }
         return (new Claim(Outcome.STARTED, runId, null));
      }
   }



   /*******************************************************************************
    ** Record that a trigger was skipped because the plan is already running: a
    ** visible run row, and a warning.
    *******************************************************************************/
   private Claim skip(Integer planId, Integer runId, boolean ownRow, String why) throws QException
   {
      String message = SKIPPED_CONCURRENT + ": " + why;
      if(ownRow)
      {
         Map<String, Serializable> values = new LinkedHashMap<>();
         values.put("status", "CANCELLED");
         values.put("endDate", now());
         values.put("errorMessage", message);
         values.put("warningCount", 1);
         catalog.update(BackupsTables.RUN, runId, values);
      }
      String planKey = String.valueOf(planId);
      try
      {
         planKey = catalog.get(BackupsTables.PLAN, planId).map(p -> p.getValueString("planKey")).orElse(planKey);
      }
      catch(QException e)
      {
         LOG.debug("Could not look up a plan key", e);
      }
      LOG.warn("A backup trigger was skipped: " + why, logPair("planId", planId));
      BackupsRuntime.getAlertSink().raise("backups.run.skipped_concurrent", "backups.run.skipped_concurrent:" + planKey, BackupAlertSink.Severity.WARNING,
         "A backup was skipped because the plan is already running", Map.of("plan", planKey));
      return (new Claim(Outcome.SKIPPED, runId, message));
   }



   /*******************************************************************************
    ** A plan that asks for client-side encryption must not silently produce plain
    ** files: refuse unless the request carries a cipher.
    *******************************************************************************/
   private static String encryptionRefusal(QRecord plan, BackupRunRequest request)
   {
      String mode = plan.getValueString("encryptionMode");
      if(mode != null && !"NONE".equals(mode) && !request.getTransform().encrypts())
      {
         return ("The plan asks for " + mode + " encryption, but no cipher is available in this version. Nothing was written.");
      }
      return (null);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static int maxConcurrentRuns()
   {
      BackupsQBitConfig config = BackupsRuntime.getConfig();
      Integer           max    = config == null ? null : config.getMaxConcurrentRuns();
      return (max == null || max < 1 ? 1 : max);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Instant now()
   {
      return (BackupsRuntime.getClock().instant());
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Instant cutoff()
   {
      return (now().minus(HEARTBEAT_STALE));
   }



   /***************************************************************************
    **
    ***************************************************************************/
   private enum Outcome
   {
      STARTED, SKIPPED, QUEUED
   }



   /***************************************************************************
    **
    ***************************************************************************/
   private record Claim(Outcome outcome, Integer runId, String message)
   {
      /*******************************************************************************
       **
       *******************************************************************************/
      BackupRunResult toResult()
      {
         BackupRunResult result = new BackupRunResult();
         result.setRunId(runId);
         result.setStatus(outcome == Outcome.SKIPPED ? SKIPPED_CONCURRENT : QUEUED);
         result.setErrorMessage(message);
         return (result);
      }
   }
}
