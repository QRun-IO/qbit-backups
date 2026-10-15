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


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupResumePlanner;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.run.ResumeState;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import static com.kingsrook.qbits.backups.processes.BackupsProcesses.line;


/*******************************************************************************
 ** Resume (spec 9): a record action on a failed or timed out run that read each
 ** table on its own.  The same run carries on: the tables that finished are kept,
 ** and the interrupted table continues from the last key of its last complete
 ** part.  A run that held one snapshot cannot be resumed (a new read would not be
 ** the same instant), and says so; Review explains why and the way out is a fresh
 ** run of the plan.
 *******************************************************************************/
public final class ResumeRunProcess
{
   public static final String NAME = "backupResumeRun";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ResumeRunProcess()
   {
   }



   /*******************************************************************************
    ** The process.
    *******************************************************************************/
   public static QProcessMetaData process()
   {
      return (BackupsProcesses.permission(BackupsProcesses.etl(NAME, "Resume", BackupsTables.RUN, Transform.class, Load.class,
         "This is a preview of where each selected run will carry on. Nothing is written until you continue.").withSupplementalMetaData(BackupsProcesses.durability()), BackupsPermissions.RUN));
   }



   /*******************************************************************************
    ** Review: what is already done, and where the interrupted table resumes.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine runs     = line(Status.OK, "run will be resumed", "runs will be resumed", "run was resumed", "runs were resumed");
      private final ProcessSummaryLine done     = line(Status.INFO, "table is complete and will be kept as it is", "tables are complete and will be kept as they are",
         "table was complete and was kept", "tables were complete and were kept");
      private final ProcessSummaryLine carryOn  = line(Status.INFO, "table will carry on from its last complete part", "tables will carry on from their last complete part",
         "table carried on from its last complete part", "tables carried on from their last complete part");
      private final ProcessSummaryLine cannot   = line(Status.ERROR, "run cannot be resumed", "runs cannot be resumed", "run could not be resumed", "runs could not be resumed");
      private final List<String>       resumeAt = new ArrayList<>();



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         BackupCatalog catalog = new BackupCatalog();
         for(QRecord run : input.getRecords())
         {
            QRecord plan = run.getValueInteger("backupPlanId") == null ? null : catalog.get(BackupsTables.PLAN, run.getValueInteger("backupPlanId")).orElse(null);
            String  why  = BackupResumePlanner.whyNotResumable(run, plan);
            if(why != null)
            {
               cannot.incrementCountAndAddPrimaryKey(run.getValue("id"));
               cannot.setMessageSuffix(": " + why);
               continue;
            }
            ResumeState state = BackupResumePlanner.plan(run);
            runs.incrementCountAndAddPrimaryKey(run.getValue("id"));
            for(Map.Entry<String, ResumeState.TableState> entry : state.tables().entrySet())
            {
               if(entry.getValue().complete())
               {
                  done.incrementCount();
               }
               else
               {
                  carryOn.incrementCount();
                  resumeAt.add(entry.getKey() + " after key " + entry.getValue().lastKey());
               }
            }
            output.getRecords().add(run);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         if(!resumeAt.isEmpty())
         {
            carryOn.setMessageSuffix(": " + String.join(", ", resumeAt.stream().limit(5).toList()));
         }
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         runs.addSelfToListIfAnyCount(lines);
         done.addSelfToListIfAnyCount(lines);
         carryOn.addSelfToListIfAnyCount(lines);
         cannot.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Result: run the engine again on each run.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine succeeded = line(Status.OK, "run will succeed", "runs succeeded", "run succeeded after being resumed", "runs succeeded after being resumed");
      private final ProcessSummaryLine warnings  = line(Status.WARNING, "run finished with warnings", "runs finished with warnings", "run finished with warnings", "runs finished with warnings");
      private final ProcessSummaryLine failed    = line(Status.ERROR, "run failed again", "runs failed again", "run failed again", "runs failed again");



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord run : input.getRecords())
         {
            BackupRunResult result = new BackupRunCoordinator().resume(run, com.kingsrook.qqq.backend.core.jobs.progress.JobProgress.of(input));
            BackupsProcesses.requireSuccessfulJob(result);
            switch(result.getStatus())
            {
               case "SUCCEEDED" -> succeeded.incrementCountAndAddPrimaryKey(run.getValue("id"));
               case "SUCCEEDED_WITH_WARNINGS" -> warnings.incrementCountAndAddPrimaryKey(run.getValue("id"));
               default ->
               {
                  failed.incrementCountAndAddPrimaryKey(run.getValue("id"));
                  failed.setMessageSuffix(result.getErrorMessage() == null ? null : ": " + result.getErrorMessage());
               }
            }
            output.getRecords().add(run);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         succeeded.addSelfToListIfAnyCount(lines);
         warnings.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
