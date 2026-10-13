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


import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
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
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** The qbit processes (spec 9).  Each is a standard streamed ETL with the standard
 ** Review and Result summary lines; none is in the nav; labels are plain.  They
 ** appear as record actions on the table that has the records they act on.
 *******************************************************************************/
public final class BackupsProcesses
{
   public static final String RUN_PLAN = "backupRunPlan";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsProcesses()
   {
   }



   /*******************************************************************************
    ** All processes.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      processes.add(permission(etl(RUN_PLAN, "Run backup now", BackupsTables.PLAN, RunPlanTransform.class, RunPlanLoad.class,
         "This is a preview of what the backup will include. Nothing is written until you continue."), BackupsPermissions.RUN));
      processes.addAll(VerifyProcesses.processes());
      processes.addAll(RestoreProcesses.processes());
      processes.addAll(CatalogProcesses.processes());
      processes.addAll(LifecycleProcesses.processes());
      return (processes);
   }



   /*******************************************************************************
    ** Record the permission a process needs.
    *******************************************************************************/
   public static QProcessMetaData permission(QProcessMetaData process, String permission)
   {
      BackupsRuntime.processPermissions().put(process.getName(), permission);
      return (process);
   }



   /*******************************************************************************
    ** A standard streamed ETL process with full validation (Review is the dry run).
    *******************************************************************************/
   public static QProcessMetaData etl(String name, String label, String table, Class<? extends AbstractTransformStep> transform,
                                      Class<? extends AbstractLoadStep> load, String preview)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, preview);
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, transform, load, values);
      process.setName(name);
      process.setLabel(label);
      process.setTableName(table);
      process.setPermissionRules(BackupsTables.permissionRules());
      return (process);
   }



   /*******************************************************************************
    ** A summary line with future (Review) and past (Result) wording.
    *******************************************************************************/
   public static ProcessSummaryLine line(Status status, String singularFuture, String pluralFuture, String singularPast, String pluralPast)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, 0, null);
      line.setSingularFutureMessage(singularFuture);
      line.setPluralFutureMessage(pluralFuture);
      line.setSingularPastMessage(singularPast);
      line.setPluralPastMessage(pluralPast);
      return (line);
   }



   /*******************************************************************************
    ** Run backup now, review: what each selected plan will back up.
    *******************************************************************************/
   public static class RunPlanTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine plans    = line(Status.OK, "plan will run", "plans will run", "plan was run", "plans were run");
      private final ProcessSummaryLine tables   = line(Status.INFO, "table will be backed up", "tables will be backed up", "table was backed up", "tables were backed up");
      private final ProcessSummaryLine excluded = line(Status.INFO, "table is left out by its role or scope", "tables are left out by their role or scope",
         "table was left out by its role or scope", "tables were left out by their role or scope");
      private final ProcessSummaryLine inactive = line(Status.WARNING, "plan is not active (it can still be run by hand)", "plans are not active (they can still be run by hand)",
         "plan was not active", "plans were not active");
      private final ProcessSummaryLine invalid  = line(Status.ERROR, "plan cannot run", "plans cannot run", "plan could not run", "plans could not run");
      private final List<String>       samples  = new ArrayList<>();



      /*******************************************************************************
       ** Resolve each plan scope without writing anything.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord plan : input.getRecords())
         {
            try
            {
               String alias = plan.getValueString("storageBucketAlias");
               if(alias == null)
               {
                  throw (new QException("no storage bucket alias"));
               }
               BackupsRuntime.storageFor(alias);
               ScopeResolver.ResolvedScope scope = BackupRunFactory.resolveScope(plan);
               plans.incrementCountAndAddPrimaryKey(plan.getValue("id"));
               tables.incrementCount(scope.tables().size());
               excluded.incrementCount(scope.excluded().size());
               if(!Boolean.TRUE.equals(plan.getValueBoolean("isActive")))
               {
                  inactive.incrementCountAndAddPrimaryKey(plan.getValue("id"));
               }
               for(ScopeResolver.ResolvedTable table : scope.tables().stream().limit(5).toList())
               {
                  samples.add(table.name());
               }
               output.getRecords().add(plan);
            }
            catch(Exception e)
            {
               invalid.incrementCountAndAddPrimaryKey(plan.getValue("id"));
               invalid.setMessageSuffix(": " + e.getMessage());
            }
         }
      }



      /*******************************************************************************
       ** Review lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         plans.addSelfToListIfAnyCount(lines);
         tables.addSelfToListIfAnyCount(lines);
         excluded.addSelfToListIfAnyCount(lines);
         inactive.addSelfToListIfAnyCount(lines);
         invalid.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Run backup now, execute: run the engine for each plan.
    *******************************************************************************/
   public static class RunPlanLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine succeeded = line(Status.OK, "backup will succeed", "backups succeeded", "backup succeeded", "backups succeeded");
      private final ProcessSummaryLine warnings  = line(Status.WARNING, "backup finished with warnings", "backups finished with warnings",
         "backup finished with warnings", "backups finished with warnings");
      private final ProcessSummaryLine failed    = line(Status.ERROR, "backup failed", "backups failed", "backup failed", "backups failed");
      private final ProcessSummaryLine skipped   = line(Status.WARNING, "backup did not start", "backups did not start", "backup did not start", "backups did not start");
      private final ProcessSummaryLine rows      = line(Status.INFO, "row was written", "rows were written", "row was written", "rows were written");
      private final ProcessSummaryLine files     = line(Status.INFO, "referenced file was copied or already stored", "referenced files were copied or already stored",
         "referenced file was copied or already stored", "referenced files were copied or already stored");
      private final List<BackupRunResult> results = new ArrayList<>();



      /*******************************************************************************
       ** Run the plans.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord plan : input.getRecords())
         {
            BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "MANUAL");
            results.add(result);
            switch(result.getStatus())
            {
               case "SUCCEEDED" -> succeeded.incrementCountAndAddPrimaryKey(result.getRunId());
               case "SUCCEEDED_WITH_WARNINGS" -> warnings.incrementCountAndAddPrimaryKey(result.getRunId());
               case BackupRunCoordinator.SKIPPED_CONCURRENT, BackupRunCoordinator.QUEUED ->
               {
                  skipped.incrementCountAndAddPrimaryKey(result.getRunId());
                  skipped.setMessageSuffix(result.getErrorMessage() == null ? null : ": " + result.getErrorMessage());
               }
               default ->
               {
                  failed.incrementCountAndAddPrimaryKey(result.getRunId());
                  failed.setMessageSuffix(result.getErrorMessage() == null ? null : ": " + result.getErrorMessage());
               }
            }
            rows.incrementCount((int) Math.min(Integer.MAX_VALUE, result.getRows()));
            files.incrementCount(result.getFilesCopied() + result.getFilesDeduplicated());
            output.getRecords().add(plan);
         }
      }



      /*******************************************************************************
       ** Result lines, with the run in the primary keys so it links to the run.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         succeeded.addSelfToListIfAnyCount(lines);
         warnings.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         skipped.addSelfToListIfAnyCount(lines);
         rows.addSelfToListIfAnyCount(lines);
         files.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
