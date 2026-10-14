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


import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.processes.DefaultPlanProcess;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The Setup card on the Backups home: what a working backup needs (a plan, a
 ** storage bucket that resolves, a schedule, verification, a separate prune
 ** bucket), whether each is in place, and the next step.  Computed from the plans
 ** the home already loaded and the runtime's storage resolver; it reads nothing
 ** else.
 *******************************************************************************/
public final class BackupsSetupStatus
{
   /*******************************************************************************
    ** OK: in place. TODO: backups need it. DEFAULT: working, with a default worth a
    ** look. OPTIONAL: a feature that is off until set up.
    *******************************************************************************/
   public enum State
   {
      OK, TODO, DEFAULT, OPTIONAL
   }



   /*******************************************************************************
    ** One line of the card.
    *******************************************************************************/
   public record Item(String label, String tableName, State state, String detail, String nextStep)
   {
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsSetupStatus()
   {
   }



   /*******************************************************************************
    ** Build the items from the plans.
    *******************************************************************************/
   public static List<Item> load(List<QRecord> plans)
   {
      BackupsQBitConfig config = BackupsRuntime.isInstalled() ? BackupsRuntime.getConfig() : null;
      return (load(plans, config != null && hasContent(config.getSchedulerName()), config == null ? null : config.getHostSchedulerDescription(),
         config != null && Boolean.TRUE.equals(config.getHostSchedulerRunsVerification())));
   }



   /*******************************************************************************
    ** Build the items from the plans, given whether a scheduler is configured (a plan
    ** cron only fires through the scheduler the qbit config names).
    *******************************************************************************/
   public static List<Item> load(List<QRecord> plans, boolean schedulerConfigured)
   {
      return (load(plans, schedulerConfigured, null, false));
   }



   /*******************************************************************************
    ** Build the items from the plans. A host that runs plan crons with its own tick
    ** (hostScheduler names it) has crons that fire; hostRunsVerification says whether
    ** that tick also runs the verify cron.
    *******************************************************************************/
   public static List<Item> load(List<QRecord> plans, boolean schedulerConfigured, String hostScheduler, boolean hostRunsVerification)
   {
      boolean host = hasContent(hostScheduler);
      List<Item> items = new ArrayList<>();
      List<QRecord> active = plans.stream().filter(p -> Boolean.TRUE.equals(p.getValueBoolean("isActive"))).toList();

      ///////////
      // plans //
      ///////////
      if(plans.isEmpty())
      {
         items.add(new Item("Backup plans", BackupsTables.PLAN, State.TODO, "No plan: nothing is backed up.",
            "Run Create default plan on Backup plans (it asks only for the storage bucket alias), or add a plan with New."));
         items.add(new Item("Plan scope rules", BackupsTables.PLAN_SCOPE, State.OPTIONAL, "None needed yet.",
            "A plan that backs up everything (scope Instance) needs no rules. Add them on a plan that is limited to apps, qbits or tables."));
         return (items);
      }
      items.add(active.isEmpty()
         ? new Item("Backup plans", BackupsTables.PLAN, State.TODO, plans.size() + (plans.size() == 1 ? " plan, but it is" : " plans, but none is") + " active: nothing runs on its own.", "Turn a plan on (Active).")
         : new Item("Backup plans", BackupsTables.PLAN, State.OK, active.size() + " active plan" + (active.size() == 1 ? "" : "s") + " of " + plans.size() + ".", null));

      /////////////////////////////////////////
      // scope rules: needed unless INSTANCE //
      /////////////////////////////////////////
      List<String> limited = plans.stream().filter(p -> p.getValueString("scopeType") != null && !"INSTANCE".equals(p.getValueString("scopeType"))).map(p -> p.getValueString("name")).toList();
      items.add(limited.isEmpty()
         ? new Item("Plan scope rules", BackupsTables.PLAN_SCOPE, State.OPTIONAL, "Every plan backs up the whole instance, so no rules are needed.",
            "Add rules to a plan only to include or leave out apps, qbits, tables or roles.")
         : new Item("Plan scope rules", BackupsTables.PLAN_SCOPE, State.OK, limited.size() + (limited.size() == 1 ? " plan is" : " plans are") + " limited by scope rules: " + String.join(", ", limited) + ".",
            "Check each plan's Scope rules to see what it covers."));

      ///////////////////////////////////////////////
      // storage: every alias a plan names resolves //
      ///////////////////////////////////////////////
      Set<String> aliases = new LinkedHashSet<>();
      for(QRecord plan : plans)
      {
         for(String field : List.of("storageBucketAlias", "pruneBucketAlias"))
         {
            if(plan.getValueString(field) != null)
            {
               aliases.add(plan.getValueString(field));
            }
         }
      }
      List<String> broken = new ArrayList<>();
      for(String alias : aliases)
      {
         try
         {
            BackupsRuntime.storageFor(alias);
         }
         catch(RuntimeException e)
         {
            broken.add(alias);
         }
      }
      items.add(broken.isEmpty()
         ? new Item("Storage", BackupsTables.PLAN, State.OK, aliases.size() + " storage bucket alias" + (aliases.size() == 1 ? "" : "es") + " in use, and each resolves.", "Run Test destination on a plan to prove it can write.")
         : new Item("Storage", BackupsTables.PLAN, State.TODO, "These aliases do not resolve to a bucket: " + String.join(", ", broken) + ".",
            "Fix the alias on the plan, or add the bucket to the application's storage resolver."));

      /////////////////////////////////////////
      // schedule: an active plan has a cron //
      /////////////////////////////////////////
      long scheduled = active.stream().filter(p -> hasContent(p.getValueString("cronExpression"))).count();
      String noScheduler = "No scheduler is configured (schedulerName on the qbit config), so plan crons are saved but never fire";
      items.add(!schedulerConfigured && !host
         ? new Item("Schedule", BackupsTables.PLAN, State.TODO, noScheduler + ". Backups run only when someone clicks Run backup now.",
            "Set schedulerName on the qbit config (or run your own tick if the application does), then give each plan a cron expression.")
         : scheduled == 0
         ? new Item("Schedule", BackupsTables.PLAN, State.TODO, "No active plan has a schedule: backups run only when someone clicks Run backup now.", "Set a Cron expression on a plan (for example " + DefaultPlanProcess.CRON + ").")
         : new Item("Schedule", BackupsTables.PLAN, State.OK, scheduled + " active plan" + (scheduled == 1 ? " runs" : "s run") + " on a schedule"
            + (host && !schedulerConfigured ? ", run by " + hostScheduler + " (the application scheduler)." : "."), null));

      //////////////////
      // verification //
      //////////////////
      long verified = active.stream().filter(p -> hasContent(p.getValueString("verifyCronExpression"))).count();
      items.add(host && !schedulerConfigured && !hostRunsVerification
         ? new Item("Verification", BackupsTables.VERIFICATION, State.OPTIONAL, "Not run by " + hostScheduler + ": verify cron expressions are saved but nothing fires them.",
            "Run Verify backup on a run by hand, or have the application scheduler run the backupVerifyPlan process for each plan.")
         : !schedulerConfigured && !host
         ? new Item("Verification", BackupsTables.VERIFICATION, State.TODO, noScheduler + ", so no verification is scheduled.",
            "Set schedulerName on the qbit config, then a verify cron expression on a plan; or run Verify backup on a run by hand.")
         : verified == 0
         ? new Item("Verification", BackupsTables.VERIFICATION, State.OPTIONAL, "No plan verifies its backups on a schedule.",
            "Set a Verify cron expression on a plan, or run Verify backup on a run. A backup nobody has restored is a guess.")
         : new Item("Verification", BackupsTables.VERIFICATION, State.OK, verified + " active plan" + (verified == 1 ? " verifies" : "s verify") + " its latest backup on a schedule.", null));

      ////////////////////////////////////////////
      // prune bucket: separate delete authority //
      ////////////////////////////////////////////
      long noPrune = plans.stream().filter(p -> !hasContent(p.getValueString("pruneBucketAlias"))).count();
      items.add(noPrune == 0
         ? new Item("Prune bucket", BackupsTables.PLAN, State.OK, "Every plan prunes through its own delete-capable bucket alias.", null)
         : new Item("Prune bucket", BackupsTables.PLAN, State.DEFAULT, noPrune + (noPrune == 1 ? " plan has" : " plans have") + " no prune bucket: Prune uses the backup bucket, so one key can both write and delete backups.",
            "Give the plan a separate delete-capable Prune bucket alias."));
      return (items);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean hasContent(String value)
   {
      return (value != null && !value.isBlank());
   }
}
