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

package com.kingsrook.qbits.backups.processes;


import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.prune.BackupPruner;
import com.kingsrook.qbits.backups.prune.PruneCalculator;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import static com.kingsrook.qbits.backups.processes.BackupsProcesses.line;


/*******************************************************************************
 ** Prune per retention (spec 9, 10.3).  A record action on backups_plan, and a
 ** table action on backups_run (which prunes the plans of the selected runs); the
 ** plan one also runs daily when the qbit has a scheduler.
 **
 ** Review shows exactly what Result will do: the runs to delete, the runs kept by
 ** class, the runs kept because an object is still locked (with the date), the
 ** debris prefixes, the content-addressed objects to sweep, and the bytes freed.
 ** Review writes nothing.
 *******************************************************************************/
public final class PruneProcess
{
   public static final String NAME      = "backupPrune";
   public static final String NAME_RUNS = "backupPruneRuns";

   private static final long MIB = 1024L * 1024L;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private PruneProcess()
   {
   }



   /*******************************************************************************
    ** The two processes.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      QProcessMetaData onPlans = BackupsProcesses.permission(BackupsProcesses.etl(NAME, "Prune per retention", BackupsTables.PLAN, PlanTransform.class, Load.class,
         "This is a preview of what Prune will delete. Nothing is deleted until you continue."), BackupsPermissions.ADMIN);

      BackupsQBitConfig config = BackupsRuntime.getConfig();
      if(config != null && config.getSchedulerName() != null && !config.getSchedulerName().isBlank())
      {
         onPlans.withSchedule(new QScheduleMetaData().withSchedulerName(config.getSchedulerName()).withRepeatSeconds(24 * 3600).withInitialDelaySeconds(3600));
      }

      QProcessMetaData onRuns = BackupsProcesses.permission(BackupsProcesses.etl(NAME_RUNS, "Prune per retention", BackupsTables.RUN, RunTransform.class, Load.class,
         "This is a preview of what Prune will delete for the plans of the selected runs. Nothing is deleted until you continue."), BackupsPermissions.ADMIN);
      return (List.of(onPlans, onRuns));
   }



   /*******************************************************************************
    ** The review lines, shared by the two transforms.
    *******************************************************************************/
   abstract static class BaseTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine plans    = line(Status.OK, "plan will be pruned", "plans will be pruned", "plan was pruned", "plans were pruned");
      private final ProcessSummaryLine delete   = line(Status.WARNING, "run will be deleted", "runs will be deleted", "run was deleted", "runs were deleted");
      private final ProcessSummaryLine daily    = line(Status.OK, "run is kept as a daily backup", "runs are kept as daily backups", "run was kept as a daily backup", "runs were kept as daily backups");
      private final ProcessSummaryLine weekly   = line(Status.OK, "run is kept as a weekly backup", "runs are kept as weekly backups", "run was kept as a weekly backup", "runs were kept as weekly backups");
      private final ProcessSummaryLine monthly  = line(Status.OK, "run is kept as a monthly backup", "runs are kept as monthly backups", "run was kept as a monthly backup", "runs were kept as monthly backups");
      private final ProcessSummaryLine yearly   = line(Status.OK, "run is kept as a yearly backup", "runs are kept as yearly backups", "run was kept as a yearly backup", "runs were kept as yearly backups");
      private final ProcessSummaryLine guarded  = line(Status.OK, "run is kept by a guard (the minimum, the newest verified run, or a recent restore)",
         "runs are kept by a guard (the minimum, the newest verified run, or a recent restore)", "run was kept by a guard", "runs were kept by a guard");
      private final ProcessSummaryLine locked   = line(Status.WARNING, "run is kept: its objects are locked", "runs are kept: their objects are locked",
         "run was kept: its objects are locked", "runs were kept: their objects are locked");
      private final ProcessSummaryLine debris   = line(Status.INFO, "unfinished backup (older than 7 days) will be deleted", "unfinished backups (older than 7 days) will be deleted",
         "unfinished backup was deleted", "unfinished backups were deleted");
      private final ProcessSummaryLine sweep    = line(Status.INFO, "stored file that no retained backup references will be deleted", "stored files that no retained backup references will be deleted",
         "stored file was deleted", "stored files were deleted");
      private final ProcessSummaryLine sweepLocked = line(Status.WARNING, "stored file is kept: it is locked", "stored files are kept: they are locked", "stored file was kept: it is locked", "stored files were kept: they are locked");
      private final ProcessSummaryLine deferred = line(Status.WARNING, "plan's file store sweep is skipped", "plans' file store sweeps are skipped", "plan's file store sweep was skipped", "plans' file store sweeps were skipped");
      private final ProcessSummaryLine freed    = line(Status.INFO, "MiB of storage will be freed", "MiB of storage will be freed", "MiB of storage was freed", "MiB of storage was freed");
      private final ProcessSummaryLine failed   = line(Status.ERROR, "plan cannot be pruned", "plans cannot be pruned", "plan could not be pruned", "plans could not be pruned");

      private final Set<Integer> seenPlans = new HashSet<>();
      private       long         bytes     = 0;
      private       Instant      latestLock;



      /*******************************************************************************
       ** The plan records this step looks at.
       *******************************************************************************/
      abstract List<QRecord> plansFor(RunBackendStepInput input) throws QException;



      /*******************************************************************************
       ** Plan each selected plan, writing nothing.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord plan : plansFor(input))
         {
            if(!seenPlans.add(plan.getValueInteger("id")))
            {
               continue;
            }
            try
            {
               BackupPruner.PrunePlan prunePlan = new BackupPruner().plan(plan);
               plans.incrementCountAndAddPrimaryKey(plan.getValue("id"));
               delete.incrementCount(prunePlan.toDelete().size());
               var kept = prunePlan.keptByClass();
               daily.incrementCount(kept.getOrDefault(PruneCalculator.RetentionClass.DAILY, 0));
               weekly.incrementCount(kept.getOrDefault(PruneCalculator.RetentionClass.WEEKLY, 0));
               monthly.incrementCount(kept.getOrDefault(PruneCalculator.RetentionClass.MONTHLY, 0));
               yearly.incrementCount(kept.getOrDefault(PruneCalculator.RetentionClass.YEARLY, 0));
               guarded.incrementCount(kept.getOrDefault(PruneCalculator.RetentionClass.NONE, 0));
               locked.incrementCount(prunePlan.lockedNotKept().size());
               for(BackupPruner.RunPlan run : prunePlan.lockedNotKept())
               {
                  latestLock = latestLock == null || run.lockedUntil().isAfter(latestLock) ? run.lockedUntil() : latestLock;
               }
               debris.incrementCount(prunePlan.debris().stream().filter(d -> d.lockedUntil() == null).toList().size());
               sweep.incrementCount(prunePlan.sweep().deletable().size());
               sweepLocked.incrementCount(prunePlan.sweep().locked().size());
               if(prunePlan.sweep().deferredReason() != null)
               {
                  deferred.incrementCount();
                  deferred.setMessageSuffix(": " + prunePlan.sweep().deferredReason());
               }
               bytes += prunePlan.bytesToFree();
               output.getRecords().add(plan);
            }
            catch(Exception e)
            {
               failed.incrementCountAndAddPrimaryKey(plan.getValue("id"));
               failed.setMessageSuffix(": " + e.getMessage());
            }
         }
      }



      /*******************************************************************************
       ** The lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         if(latestLock != null)
         {
            locked.setMessageSuffix(" (the latest lock ends " + latestLock + ")");
         }
         freed.setCount((int) Math.min(Integer.MAX_VALUE, (bytes + MIB - 1) / MIB));

         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         plans.addSelfToListIfAnyCount(lines);
         delete.addSelfToListIfAnyCount(lines);
         daily.addSelfToListIfAnyCount(lines);
         weekly.addSelfToListIfAnyCount(lines);
         monthly.addSelfToListIfAnyCount(lines);
         yearly.addSelfToListIfAnyCount(lines);
         guarded.addSelfToListIfAnyCount(lines);
         locked.addSelfToListIfAnyCount(lines);
         debris.addSelfToListIfAnyCount(lines);
         sweep.addSelfToListIfAnyCount(lines);
         sweepLocked.addSelfToListIfAnyCount(lines);
         deferred.addSelfToListIfAnyCount(lines);
         freed.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Review for plans.
    *******************************************************************************/
   public static class PlanTransform extends BaseTransform
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      List<QRecord> plansFor(RunBackendStepInput input)
      {
         return (input.getRecords());
      }
   }



   /*******************************************************************************
    ** Review for runs: the plans of the selected runs.
    *******************************************************************************/
   public static class RunTransform extends BaseTransform
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      List<QRecord> plansFor(RunBackendStepInput input) throws QException
      {
         List<QRecord> plans = new ArrayList<>();
         BackupCatalog catalog = new BackupCatalog();
         for(QRecord run : input.getRecords())
         {
            if(run.getValueInteger("backupPlanId") != null)
            {
               catalog.get(BackupsTables.PLAN, run.getValueInteger("backupPlanId")).ifPresent(plans::add);
            }
         }
         return (plans);
      }
   }



   /*******************************************************************************
    ** Result: do it, and report what was done.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine plans     = line(Status.OK, "plan will be pruned", "plans will be pruned", "plan was pruned", "plans were pruned");
      private final ProcessSummaryLine deleted   = line(Status.OK, "run will be deleted", "runs will be deleted", "run was deleted", "runs were deleted");
      private final ProcessSummaryLine locked    = line(Status.WARNING, "run was kept: locked until a later date", "runs were kept: locked until a later date",
         "run was kept: locked until a later date", "runs were kept: locked until a later date");
      private final ProcessSummaryLine debris    = line(Status.OK, "unfinished backup will be deleted", "unfinished backups will be deleted", "unfinished backup was deleted", "unfinished backups were deleted");
      private final ProcessSummaryLine files     = line(Status.OK, "stored file will be deleted", "stored files will be deleted", "stored file was deleted", "stored files were deleted");
      private final ProcessSummaryLine filesKept = line(Status.WARNING, "stored file was kept: it is locked", "stored files were kept: they are locked", "stored file was kept: it is locked", "stored files were kept: they are locked");
      private final ProcessSummaryLine notes     = line(Status.INFO, "note", "notes", "note", "notes");
      private final ProcessSummaryLine freed     = line(Status.INFO, "MiB of storage was freed", "MiB of storage was freed", "MiB of storage was freed", "MiB of storage was freed");
      private final ProcessSummaryLine failed    = line(Status.ERROR, "plan could not be pruned", "plans could not be pruned", "plan could not be pruned", "plans could not be pruned");
      private       long               bytes     = 0;
      private final List<String>       messages  = new ArrayList<>();



      /*******************************************************************************
       ** Prune each plan.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         com.kingsrook.qqq.backend.core.model.session.QSession session = com.kingsrook.qqq.backend.core.context.QContext.getQSession();
         String user = session == null || session.getUser() == null ? "system:scheduler" : session.getUser().getIdReference();
         for(QRecord plan : input.getRecords())
         {
            try
            {
               BackupPruner.Outcome outcome = new BackupPruner().prune(plan, user);
               plans.incrementCountAndAddPrimaryKey(plan.getValue("id"));
               deleted.incrementCount(outcome.runsDeleted());
               locked.incrementCount(outcome.runsLocked());
               debris.incrementCount(outcome.debrisPrefixesDeleted());
               files.incrementCount(outcome.filesSwept());
               filesKept.incrementCount(outcome.filesLocked());
               bytes += outcome.bytesFreed();
               messages.addAll(outcome.messages());
               output.getRecords().add(plan);
            }
            catch(Exception e)
            {
               failed.incrementCountAndAddPrimaryKey(plan.getValue("id"));
               failed.setMessageSuffix(": " + e.getMessage());
            }
         }
      }



      /*******************************************************************************
       ** The Result lines: what was deleted, and what was kept because it is locked.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         freed.setCount((int) Math.min(Integer.MAX_VALUE, (bytes + MIB - 1) / MIB));
         notes.setCount(messages.size());
         if(!messages.isEmpty())
         {
            notes.setMessageSuffix(": " + String.join("; ", messages.stream().limit(5).toList()) + (messages.size() > 5 ? "; and " + (messages.size() - 5) + " more" : ""));
         }
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         plans.addSelfToListIfAnyCount(lines);
         deleted.addSelfToListIfAnyCount(lines);
         locked.addSelfToListIfAnyCount(lines);
         debris.addSelfToListIfAnyCount(lines);
         files.addSelfToListIfAnyCount(lines);
         filesKept.addSelfToListIfAnyCount(lines);
         freed.addSelfToListIfAnyCount(lines);
         notes.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
