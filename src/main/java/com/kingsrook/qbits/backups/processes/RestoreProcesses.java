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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.restore.RestoreEngine;
import com.kingsrook.qbits.backups.restore.RestoreOutcome;
import com.kingsrook.qbits.backups.restore.RestoreRequest;
import com.kingsrook.qbits.backups.restore.RestoreSummaries;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
import com.kingsrook.qqq.backend.core.jobs.progress.JobPhase;
import com.kingsrook.qqq.backend.core.jobs.progress.JobProgress;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** The restore processes (spec 9.3): Restore... on a backup run, and Restore this
 ** table on a backup artifact.  Both are the standard streamed ETL.  The first
 ** screen takes the inputs; Review is a real dry run of the whole restore with the
 ** per-table summary lines and a masked sample diff; Execute is the restore, and
 ** Result shows what was done.  Neither is in the nav.
 *******************************************************************************/
public final class RestoreProcesses
{
   public static final String RESTORE       = "backupRestore";
   public static final String RESTORE_TABLE = "backupRestoreTable";

   public static final String STEP_INPUT = "input";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private RestoreProcesses()
   {
   }



   /*******************************************************************************
    ** The processes defined here.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      processes.add(BackupsProcesses.permission(restore(RESTORE, "Restore...", BackupsTables.RUN), BackupsPermissions.RESTORE));
      processes.add(BackupsProcesses.permission(restore(RESTORE_TABLE, "Restore this table", BackupsTables.ARTIFACT), BackupsPermissions.RESTORE));
      return (processes);
   }



   /*******************************************************************************
    ** One restore process, sourced from the table whose records it is run on.
    *******************************************************************************/
   private static QProcessMetaData restore(String name, String label, String table)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_TRANSACTION_LEVEL, StreamedETLWithFrontendProcess.TRANSACTION_LEVEL_AUTO_COMMIT);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, "This is what the restore will do. Nothing is written until you continue.");

      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(ExtractViaQueryStep.class, RestoreTransform.class, RestoreLoad.class, values);
      process.withStep(0, inputStep());
      process.setName(name);
      process.setLabel(label);
      process.setTableName(table);
      process.setPermissionRules(BackupsTables.permissionRules());
      return (process.withSupplementalMetaData(BackupsProcesses.durability()));
   }



   /*******************************************************************************
    ** The first screen: what to restore and how.
    *******************************************************************************/
   private static QFrontendStepMetaData inputStep()
   {
      return (new QFrontendStepMetaData()
         .withName(STEP_INPUT)
         .withLabel("Restore options")
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM))
         .withFormField(new QFieldMetaData("tables", QFieldType.STRING).withLabel("Tables (blank for every table in the backup)"))
         .withFormField(new QFieldMetaData("tableFilters", QFieldType.TEXT).withLabel("Only these records (JSON: table name to filter, optional)"))
         .withFormField(new QFieldMetaData("targetTable", QFieldType.STRING).withLabel("Restore into another table (optional, one table only)"))
         .withFormField(new QFieldMetaData("mode", QFieldType.STRING).withLabel("Mode").withIsRequired(true).withDefaultValue(RestoreRequest.MODE_INSERT_NEW)
            .withPossibleValueSourceName(BackupsTables.pvs("restore_mode")))
         .withFormField(new QFieldMetaData("matchOn", QFieldType.STRING).withLabel("Match records on (PRIMARY_KEY, or a unique key's fields)").withDefaultValue(RestoreRequest.MATCH_PRIMARY_KEY))
         .withFormField(new QFieldMetaData("idStrategy", QFieldType.STRING).withLabel("Ids").withIsRequired(true).withDefaultValue(RestoreRequest.IDS_PRESERVE)
            .withPossibleValueSourceName(BackupsTables.pvs("id_strategy")))
         .withFormField(new QFieldMetaData("suppressAutomations", QFieldType.BOOLEAN).withLabel("Do not trigger automations").withDefaultValue(true))
         .withFormField(new QFieldMetaData("publishEvents", QFieldType.BOOLEAN).withLabel("Publish change events").withDefaultValue(false))
         .withFormField(new QFieldMetaData("runCustomizers", QFieldType.BOOLEAN).withLabel("Run table customizers").withDefaultValue(false))
         .withFormField(new QFieldMetaData("perRecordAudit", QFieldType.BOOLEAN).withLabel("Audit each record (otherwise one summary per table)").withDefaultValue(false))
         .withFormField(new QFieldMetaData("restoreFiles", QFieldType.BOOLEAN).withLabel("Restore referenced files").withDefaultValue(true))
         .withFormField(new QFieldMetaData("fileTargetBucketAlias", QFieldType.STRING).withLabel("Restore files to another bucket alias (optional)"))
         .withFormField(new QFieldMetaData("fileTargetPrefix", QFieldType.STRING).withLabel("Restore files under this prefix (optional)"))
         .withFormField(new QFieldMetaData("preRestoreBackup", QFieldType.BOOLEAN).withLabel("Back up the target first").withDefaultValue(true))
         .withFormField(new QFieldMetaData("confirmationText", QFieldType.STRING).withLabel("Type the table name (or REPLACE <n> TABLES) to confirm a REPLACE")));
   }



   /*******************************************************************************
    ** The backup runs the selected records belong to, with the tables chosen for each
    ** (empty means the tables of the form).  Artifact records choose their own table.
    *******************************************************************************/
   static Map<Integer, Set<String>> sources(List<QRecord> records) throws QException
   {
      Map<Integer, Set<String>> sources = new LinkedHashMap<>();
      for(QRecord record : records)
      {
         boolean artifact = BackupsTables.ARTIFACT.equals(record.getTableName()) || record.getValue("artifactType") != null;
         if(artifact)
         {
            String table = record.getValueString("tableName");
            if(table == null)
            {
               throw (new QUserFacingException("Only the data of a table can be restored: choose a table data artifact."));
            }
            sources.computeIfAbsent(record.getValueInteger("backupRunId"), k -> new LinkedHashSet<>()).add(table);
         }
         else
         {
            sources.computeIfAbsent(record.getValueInteger("id"), k -> new LinkedHashSet<>());
         }
      }
      return (sources);
   }



   /*******************************************************************************
    ** Review (preview, validate) is the dry run; execute only re-checks the rails.
    *******************************************************************************/
   public static class RestoreTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final List<RestoreOutcome> outcomes = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         boolean execute = StreamedETLWithFrontendProcess.STEP_NAME_EXECUTE.equals(input.getStepName());
         RestoreEngine engine = new RestoreEngine();
         for(Map.Entry<Integer, Set<String>> source : sources(input.getRecords()).entrySet())
         {
            RestoreRequest request = RestoreRequest.fromInput(input, source.getKey(), new ArrayList<>(source.getValue()));
            if(execute)
            {
               RestoreEngine.Plan plan = engine.plan(request);
               if(!plan.errors().isEmpty())
               {
                  throw (new QUserFacingException(String.join(" ", plan.errors())));
               }
            }
            else
            {
               outcomes.add(engine.review(request));
            }
         }
         output.getRecords().addAll(input.getRecords());
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         for(RestoreOutcome outcome : outcomes)
         {
            lines.addAll(RestoreSummaries.lines(outcome, false));
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Execute: the restore itself, one backup run at a time.
    *******************************************************************************/
   public static class RestoreLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final List<RestoreOutcome> outcomes = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         JobProgress progress = JobProgress.of(input);
         progress.phases(JobPhase.of("restore", "Restore backups", 100)).phase("restore");
         Map<Integer, Set<String>> sources = sources(input.getRecords());
         progress.total(sources.size(), "backups").done(0);
         int done = 0;
         RestoreEngine engine = new RestoreEngine();
         try
         {
            for(Map.Entry<Integer, Set<String>> source : sources.entrySet())
            {
               progress.message("Restoring backup " + source.getKey());
               RestoreRequest request = RestoreRequest.fromInput(input, source.getKey(), new ArrayList<>(source.getValue()));
               outcomes.add(engine.execute(request));
               progress.info("Restored backup " + source.getKey());
               progress.done(++done);
            }
            progress.getSnapshot().getPhases().getFirst().setStatus(JobPhase.Status.DONE);
            output.getRecords().addAll(input.getRecords());
         }
         catch(QException | RuntimeException e)
         {
            progress.getSnapshot().getPhases().getFirst().setStatus(JobPhase.Status.FAILED);
            if(com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException.find(e) == null)
            {
               progress.error("Restore failed (" + e.getClass().getSimpleName() + ")");
            }
            throw (e);
         }
         finally
         {
            progress.flush();
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         for(RestoreOutcome outcome : outcomes)
         {
            lines.addAll(RestoreSummaries.lines(outcome, true));
         }
         return (lines);
      }
   }
}
