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

package com.kingsrook.qbits.backups.alerts;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;


/*******************************************************************************
 ** Every alert the Backups qbit raises (design spec section 10.4), with stable
 ** dedupe keys, and helpers that send them through the bound BackupAlertSink.
 **
 ** A dedupe key is the alert type plus a plan key (or a run key, or a plan key
 ** and a verify level), so a repeated condition refreshes one alert and a cleared
 ** condition resolves exactly that alert.  Details carry counts and names only:
 ** never row values, object keys with secrets in them, or presigned URLs.
 *******************************************************************************/
public final class BackupAlerts
{
   public static final String PLAN_MISSED          = "backups.plan.missed";
   public static final String RUN_FAILED           = "backups.run.failed";
   public static final String RUN_MISSING          = "backups.run.missing-in-storage";
   public static final String FILES_MISSING        = "backups.files.missing";
   public static final String VERIFY_FAILED        = "backups.verify.failed";
   public static final String VERIFY_OVERDUE       = "backups.verify.overdue";
   public static final String PLAN_OWNER_INVALID   = "backups.plan.owner-invalid";
   public static final String STORAGE_DEBRIS       = "backups.storage.debris";
   public static final String RESTORE_COMPLETED    = "backups.restore.completed";

   /** The prefix of the heartbeat monitor key of a plan. */
   public static final String HEARTBEAT_PREFIX = "backups.plan:";



   /***************************************************************************
    ** A declared alert type, for the Alerts qbit's type registry.
    **
    ** @param name the alert type name
    ** @param label a short label
    ** @param defaultSeverity the severity when a signal does not say
    ** @param howToFix one sentence for the person looking at the alert
    ***************************************************************************/
   public record AlertType(String name, String label, BackupAlertSink.Severity defaultSeverity, String howToFix)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupAlerts()
   {
   }



   /*******************************************************************************
    ** The alert types of the spec table (section 10.4), in table order.
    *******************************************************************************/
   public static List<AlertType> alertTypes()
   {
      return (List.of(
         new AlertType(PLAN_MISSED, "Backup did not run", BackupAlertSink.Severity.ERROR,
            "Open the plan and its latest runs, check the schedule and the scheduler, then run the backup by hand."),
         new AlertType(RUN_FAILED, "Backup run failed", BackupAlertSink.Severity.WARNING,
            "Open the failed run for the error, fix the cause, and run the plan again."),
         new AlertType(RUN_MISSING, "Backup is missing from storage", BackupAlertSink.Severity.CRITICAL,
            "The catalog says the run succeeded but its manifest is gone from the bucket. Check the bucket, its lifecycle rules and the credentials, then run a new backup."),
         new AlertType(FILES_MISSING, "Backed up files were missing at the source", BackupAlertSink.Severity.WARNING,
            "Open the run's file index: records point at files that no longer exist."),
         new AlertType(VERIFY_FAILED, "Backup verification failed", BackupAlertSink.Severity.ERROR,
            "Open the verification for the mismatches. Treat the run as unusable and take a new backup."),
         new AlertType(VERIFY_OVERDUE, "Backup verification is overdue", BackupAlertSink.Severity.WARNING,
            "Run Verify backup on a recent run of the plan, or set a verify schedule on the plan."),
         new AlertType(PLAN_OWNER_INVALID, "Backup plan owner is not valid", BackupAlertSink.Severity.WARNING,
            "Set an owner who exists and holds backups.admin on the plan."),
         new AlertType(STORAGE_DEBRIS, "Backup storage has debris or orphans", BackupAlertSink.Severity.INFO,
            "Prune clears debris. Orphan prefixes can be imported into the catalog or deleted."),
         new AlertType(RESTORE_COMPLETED, "A restore finished", BackupAlertSink.Severity.INFO,
            "Nothing to fix unless the restore failed. Open the restore run.")));
   }



   /*******************************************************************************
    ** The stable dedupe key for a type and an id (plan key, run key...).
    *******************************************************************************/
   public static String key(String type, String id)
   {
      return (type + ":" + id);
   }



   /*******************************************************************************
    ** The dedupe key of a verify failure: it includes the level so a pass at a
    ** higher level clears the lower failures, and a cheap pass never hides a
    ** content failure.
    *******************************************************************************/
   public static String verifyKey(String planKey, String level)
   {
      return (VERIFY_FAILED + ":" + planKey + ":" + level);
   }



   /*******************************************************************************
    ** Raise (or refresh) an alert.  Never throws: an alerting problem must not fail
    ** a backup or a check.
    *******************************************************************************/
   public static void raise(String type, String id, BackupAlertSink.Severity severity, String title, String... detailPairs)
   {
      try
      {
         Map<String, String> detail = new LinkedHashMap<>();
         for(int i = 0; i + 1 < detailPairs.length; i += 2)
         {
            detail.put(detailPairs[i], detailPairs[i + 1]);
         }
         BackupsRuntime.getAlertSink().raise(type, key(type, id), severity, title, detail);
      }
      catch(RuntimeException e)
      {
         // fire and forget
      }
   }



   /*******************************************************************************
    ** Raise with an explicit dedupe key.
    *******************************************************************************/
   public static void raiseWithKey(String type, String dedupeKey, BackupAlertSink.Severity severity, String title, Map<String, String> detail)
   {
      try
      {
         BackupsRuntime.getAlertSink().raise(type, dedupeKey, severity, title, detail);
      }
      catch(RuntimeException e)
      {
         // fire and forget
      }
   }



   /*******************************************************************************
    ** Resolve the alert of a type and id.  Never throws.
    *******************************************************************************/
   public static void resolve(String type, String id)
   {
      resolveKey(type, key(type, id));
   }



   /*******************************************************************************
    ** Resolve an alert by its dedupe key.  Never throws.
    *******************************************************************************/
   public static void resolveKey(String type, String dedupeKey)
   {
      try
      {
         BackupsRuntime.getAlertSink().resolve(type, dedupeKey);
      }
      catch(RuntimeException e)
      {
         // fire and forget
      }
   }



   /*******************************************************************************
    ** A restore finished: an info alert, so restores are never silent (error when
    ** it failed).  The Alerts qbit clears it after 7 days; nothing resolves it here.
    *******************************************************************************/
   public static void restoreCompleted(String restoreRunKey, String status, String planKey, long rowsRestored)
   {
      boolean failed = "FAILED".equals(status);
      raise(RESTORE_COMPLETED, restoreRunKey, failed ? BackupAlertSink.Severity.ERROR : BackupAlertSink.Severity.INFO,
         failed ? "A restore failed" : "A restore finished",
         "restoreRun", restoreRunKey, "status", String.valueOf(status), "plan", String.valueOf(planKey), "rows", String.valueOf(rowsRestored));
   }
}
