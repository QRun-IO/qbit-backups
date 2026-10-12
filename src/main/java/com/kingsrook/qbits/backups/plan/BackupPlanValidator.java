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

package com.kingsrook.qbits.backups.plan;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.permissions.BackupsPermissionChecker;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;


/*******************************************************************************
 ** The rules a backup plan must satisfy when it is saved or activated (spec 4.1,
 ** 6.2, 11.1, 11.3): the refusals are clear messages, not run-time failures.
 **
 ** - the saving user has backups.admin and may read every table the plan resolves
 **   to (a scheduled run is full-fidelity, so whoever activates the plan must be
 **   allowed to read all of it)
 ** - an object lock mode is checked against what the storage bucket can do
 ** - INCLUDE_ENCRYPTED_ONLY needs encryption
 ** - formats, compressions and options that are not built yet are refused
 ** - the plan key is a slug, and cannot change after the first run (it is in every
 **   object key)
 *******************************************************************************/
public final class BackupPlanValidator
{
   private static final Pattern PLAN_KEY = Pattern.compile("[a-z0-9][a-z0-9_-]{0,59}");

   private static final Set<String> FORMATS      = Set.of("JSONL", "CSV", "JSONL_AND_CSV");
   private static final Set<String> COMPRESSIONS = Set.of("GZIP", "NONE");
   private static final Set<String> LOCK_MODES   = Set.of("NONE", "GOVERNANCE", "COMPLIANCE");

   /** Replace with the real check when client-side encryption ships (spec 11.2). */
   private static volatile boolean encryptionAvailable = false;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupPlanValidator()
   {
   }



   /*******************************************************************************
    ** Tell the validator that client-side encryption is available (the encryption
    ** work calls this when it installs a cipher).
    *******************************************************************************/
   public static void setEncryptionAvailable(boolean available)
   {
      encryptionAvailable = available;
   }



   /*******************************************************************************
    ** The outcome of a validation.
    *******************************************************************************/
   public record Result(List<String> errors, List<String> warnings)
   {
      /*******************************************************************************
       **
       *******************************************************************************/
      public boolean ok()
      {
         return (errors.isEmpty());
      }
   }



   /*******************************************************************************
    ** Validate the complete settings of a plan.
    **
    ** @param settings the plan values as they will be after the save
    ** @param oldPlanKey the plan key before the save, null for a new plan
    ** @param hasRun whether the plan already has a run
    ** @param rules the scope rules of the plan
    *******************************************************************************/
   public static Result validate(PlanSettings settings, String oldPlanKey, boolean hasRun, List<ScopeResolver.ScopeRule> rules)
   {
      List<String> errors   = new ArrayList<>();
      List<String> warnings = new ArrayList<>();

      validateKey(settings, oldPlanKey, hasRun, errors);
      validateFormats(settings, errors);
      validateEncryption(settings, errors);
      validateStorage(settings, errors, warnings);
      validateSchedule(settings, errors);
      validateNumbers(settings, errors);

      if(settings.bool("isActive", true))
      {
         validateOwner(settings, rules, errors);
      }

      return (new Result(errors, warnings));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void validateKey(PlanSettings settings, String oldPlanKey, boolean hasRun, List<String> errors)
   {
      String planKey = settings.string("planKey", null);
      if(planKey == null || !PLAN_KEY.matcher(planKey).matches())
      {
         errors.add("The plan key must be lowercase letters, digits, dash or underscore (up to 60 characters): it is part of every object key.");
      }
      if(oldPlanKey != null && planKey != null && !oldPlanKey.equals(planKey) && hasRun)
      {
         errors.add("The plan key cannot change after the plan has run: it is part of the object keys of its backups. Create a new plan instead.");
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void validateFormats(PlanSettings settings, List<String> errors)
   {
      String format = settings.string("format", "JSONL");
      if("PARQUET".equals(format))
      {
         errors.add("The PARQUET format is not available yet. Use JSONL, CSV or JSONL_AND_CSV.");
      }
      else if(!FORMATS.contains(format))
      {
         errors.add("Unknown format " + format + ". Use JSONL, CSV or JSONL_AND_CSV.");
      }

      String compression = settings.string("compression", "GZIP");
      if("ZSTD".equals(compression))
      {
         errors.add("ZSTD compression is not available yet. Use GZIP or NONE.");
      }
      else if(!COMPRESSIONS.contains(compression))
      {
         errors.add("Unknown compression " + compression + ". Use GZIP or NONE.");
      }

      if("CHANGED_SINCE_LAST".equals(settings.string("incrementalMode", "FULL")))
      {
         errors.add("Incremental backups are not available yet. Use FULL.");
      }
      if(!"NONE".equals(settings.string("nativeDump", "NONE")))
      {
         errors.add("Native database dumps are not available yet. Use NONE.");
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void validateEncryption(PlanSettings settings, List<String> errors)
   {
      String mode   = settings.string("encryptionMode", "NONE");
      String policy = settings.string("sensitiveFieldPolicy", "EXCLUDE");
      if("CLIENT_AES256_GCM".equals(mode))
      {
         if(!encryptionAvailable)
         {
            errors.add("Client-side encryption is not available in this version. Use NONE (and keep the sensitive field policy at EXCLUDE).");
         }
         else if(settings.string("encryptionKeyRef", null) == null)
         {
            errors.add("Client-side encryption needs an encryption key reference (the name of the key, never the key itself).");
         }
      }
      if("INCLUDE_ENCRYPTED_ONLY".equals(policy) && !"CLIENT_AES256_GCM".equals(mode))
      {
         errors.add("The sensitive field policy INCLUDE_ENCRYPTED_ONLY requires client-side encryption (CLIENT_AES256_GCM).");
      }
      if("INCLUDE".equals(policy) && !BackupsPermissionChecker.has(BackupsPermissions.ADMIN))
      {
         errors.add("Including sensitive fields in the clear needs the backups.admin permission.");
      }
   }



   /*******************************************************************************
    ** The destination: the bucket exists, and an object lock is something the bucket can do.
    *******************************************************************************/
   private static void validateStorage(PlanSettings settings, List<String> errors, List<String> warnings)
   {
      String alias = settings.string("storageBucketAlias", null);
      if(alias == null)
      {
         errors.add("Choose a storage bucket for the backups.");
         return;
      }

      BackupStorage storage = null;
      try
      {
         storage = BackupsRuntime.storageFor(alias);
      }
      catch(RuntimeException e)
      {
         errors.add("The storage bucket " + alias + " cannot be used: " + e.getMessage());
      }

      String lockMode = settings.string("objectLockMode", "NONE");
      if(!LOCK_MODES.contains(lockMode))
      {
         errors.add("Unknown object lock mode " + lockMode + ". Use NONE, GOVERNANCE or COMPLIANCE.");
      }
      else if(!"NONE".equals(lockMode))
      {
         Integer days = settings.integer("objectLockDays", null);
         if(days == null || days < 1)
         {
            errors.add("Object lock needs a number of days to keep each object locked (objectLockDays).");
         }
         if(storage != null && !storage.capabilities().contains(BackupStorage.StorageCapability.OBJECT_LOCK))
         {
            errors.add("Object lock " + lockMode + " was asked for, but the storage bucket " + alias + " does not support object lock (its capabilities: "
               + storage.capabilities() + "). Choose NONE, or a bucket whose Test connection found object lock supported.");
         }
      }

      String pruneAlias = settings.string("pruneBucketAlias", null);
      if(pruneAlias == null)
      {
         warnings.add("No prune bucket is set: Prune will use the backup bucket, so one key can both write and delete backups. Give the plan a separate delete-capable alias.");
      }
      else
      {
         try
         {
            BackupsRuntime.storageFor(pruneAlias);
         }
         catch(RuntimeException e)
         {
            errors.add("The prune bucket " + pruneAlias + " cannot be used: " + e.getMessage());
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void validateSchedule(PlanSettings settings, List<String> errors)
   {
      for(String field : List.of("cronExpression", "verifyCronExpression"))
      {
         String cron = settings.string(field, null);
         if(cron != null && !org.quartz.CronExpression.isValidExpression(cron))
         {
            errors.add("The " + field + " is not a valid cron expression: " + cron);
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void validateNumbers(PlanSettings settings, List<String> errors)
   {
      for(String field : List.of("retainDaily", "retainWeekly", "retainMonthly", "retainYearly", "retainMinimum"))
      {
         Integer value = settings.integer(field, 0);
         if(value < 0)
         {
            errors.add(field + " cannot be negative.");
         }
      }
      if(settings.integer("slaHours", 26) < 1)
      {
         errors.add("slaHours must be at least 1.");
      }
      if(settings.integer("pageSize", 5000) < 1)
      {
         errors.add("pageSize must be at least 1.");
      }
   }



   /*******************************************************************************
    ** Saving or activating a plan: the saving user must have backups.admin and read
    ** access to every table the plan resolves to (spec 11.1).
    *******************************************************************************/
   private static void validateOwner(PlanSettings settings, List<ScopeResolver.ScopeRule> rules, List<String> errors)
   {
      if(!BackupsPermissionChecker.has(BackupsPermissions.ADMIN))
      {
         errors.add("Saving an active plan needs the backups.admin permission: the plan runs on its own with full access.");
         return;
      }

      ScopeResolver.ResolvedScope scope;
      try
      {
         scope = new ScopeResolver(QContext.getQInstance()).resolve(settings, rules);
      }
      catch(RuntimeException e)
      {
         errors.add("The plan scope cannot be resolved: " + e.getMessage());
         return;
      }

      List<String> unreadable = new ArrayList<>();
      for(ScopeResolver.ResolvedTable table : scope.tables())
      {
         if(!PermissionsHelper.hasTablePermission(new QueryInput(table.name()), table.name(), TablePermissionSubType.READ))
         {
            unreadable.add(table.name());
         }
      }
      if(!unreadable.isEmpty())
      {
         errors.add("The plan covers tables that its owner is not allowed to read, so it could not back them up in full: "
            + String.join(", ", unreadable.stream().limit(10).toList()) + (unreadable.size() > 10 ? " and " + (unreadable.size() - 10) + " more" : "")
            + ". Remove them from the scope, or save the plan as a user who can read them.");
      }
   }
}
