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
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.plan.BackupPlanValidator;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.help.QHelpContent;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractExtractStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** "Create default plan": a table action on backups_plan that adds one working
 ** plan, "Nightly full", so a fresh install has something to run.  The only thing
 ** it asks for is the storage bucket alias, because that is the one value the qbit
 ** cannot know (it names a bucket the host's storage resolver provides).  Review
 ** shows the plan that will be created and checks it against the same rules a
 ** hand-made plan must pass; Result links the new plan.
 **
 ** Insert-only: when a plan with the key nightly-full exists (edited or not), nothing
 ** is created or changed.  The plan goes through the normal insert, so the owner is
 ** the user running the action and the schedule syncs like any other plan.
 *******************************************************************************/
public final class DefaultPlanProcess
{
   public static final String NAME        = "backupsPlanCreateDefault";
   public static final String FIELD_ALIAS = "storageBucketAlias";
   public static final String PLAN_KEY    = "nightly-full";
   public static final String PLAN_NAME   = "Nightly full";
   public static final String CRON        = "0 0 2 * * ?";
   public static final String VERIFY_CRON = "0 0 4 ? * SUN";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private DefaultPlanProcess()
   {
   }



   /*******************************************************************************
    ** The process: a table-level action with one question (the bucket alias).
    *******************************************************************************/
   public static QProcessMetaData process()
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, BackupsTables.PLAN);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, BackupsTables.PLAN);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, "This plan will be created. Nothing is written until you continue. You can edit every setting afterwards.");
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(Extract.class, Transform.class, Load.class, values);
      process.setName(NAME);
      process.setLabel("Create default plan");
      process.setIcon(new QIcon("playlist_add"));
      process.setTableName(BackupsTables.PLAN);
      process.withStep(0, new QFrontendStepMetaData()
         .withName("input")
         .withLabel("Where should the backups go?")
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM))
         .withFormField(new QFieldMetaData(FIELD_ALIAS, QFieldType.STRING).withLabel("Storage bucket alias").withIsRequired(true)
            .withHelpContent(new QHelpContent("The alias of a bucket your application provides to Backups (never a key or an endpoint). It becomes the plan's Storage bucket alias."))));
      process.setMinInputRecords(0);
      process.setPermissionRules(BackupsTables.permissionRules());
      return (BackupsProcesses.permission(process, BackupsPermissions.ADMIN));
   }



   /*******************************************************************************
    ** The values of the default plan for a bucket alias.  Every setting is explicit
    ** so the plan does not depend on the backend applying field defaults.
    *******************************************************************************/
   public static Map<String, Serializable> planValues(String alias)
   {
      Map<String, Serializable> plan = new LinkedHashMap<>();
      plan.put("name", PLAN_NAME);
      plan.put("planKey", PLAN_KEY);
      plan.put("description", "Every night: all data and audit tables of the application, and the files they reference, to the storage bucket. Edit any setting on this plan.");
      plan.put("isActive", true);
      plan.put("scopeType", "INSTANCE");
      plan.put("includeRoles", "DATA,AUDIT");
      plan.put("storageBucketAlias", alias);
      plan.put("critical", true);
      plan.put("format", "JSONL");
      plan.put("compression", "GZIP");
      plan.put("encryptionMode", "NONE");
      plan.put("sensitiveFieldPolicy", "EXCLUDE");
      plan.put("heavyFieldPolicy", "INLINE");
      plan.put("fileObjectPolicy", "COPY");
      plan.put("fileMissingPolicy", "WARN");
      plan.put("consistency", "SNAPSHOT_PREFERRED");
      plan.put("useReadReplica", false);
      plan.put("incrementalMode", "FULL");
      plan.put("nativeDump", "NONE");
      plan.put("cronExpression", CRON);
      plan.put("cronTimeZoneId", "UTC");
      plan.put("slaHours", 26);
      plan.put("verifySlaDays", 8);
      plan.put("verifyFileSamplePercent", 100);
      plan.put("verifyCronExpression", VERIFY_CRON);
      plan.put("verifyLevel", "CONTENT");
      plan.put("retainDaily", 7);
      plan.put("retainWeekly", 4);
      plan.put("retainMonthly", 12);
      plan.put("retainYearly", 0);
      plan.put("retainMinimum", 3);
      plan.put("objectLockMode", "NONE");
      plan.put("maxDurationMinutes", 240);
      plan.put("pageSize", 5000);
      plan.put("targetPartBytes", 268435456L);
      return (plan);
   }



   /*******************************************************************************
    ** Whether the default plan already exists.
    *******************************************************************************/
   private static boolean exists() throws QException
   {
      return (!new BackupCatalog().where(BackupsTables.PLAN, "planKey", PLAN_KEY).isEmpty());
   }



   /*******************************************************************************
    ** Extract: the plan to create, or nothing when it exists already.
    *******************************************************************************/
   public static class Extract extends AbstractExtractStep
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String alias = input.getValueString(FIELD_ALIAS);
         if(alias == null || alias.isBlank())
         {
            throw (new QUserFacingException("Enter the storage bucket alias the backups should go to."));
         }
         if(!exists())
         {
            QRecord record = new QRecord().withTableName(BackupsTables.PLAN);
            record.setValues(new LinkedHashMap<>(planValues(alias.strip())));
            getRecordPipe().addRecord(record);
         }
      }
   }



   /*******************************************************************************
    ** Review: the plan, checked against the rules a plan must pass.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine create   = BackupsProcesses.line(Status.OK, "plan will be created", "plans will be created", "plan was created", "plans were created");
      private final ProcessSummaryLine invalid  = BackupsProcesses.line(Status.ERROR, "plan cannot be created", "plans cannot be created", "plan could not be created", "plans could not be created");
      private final List<String>       problems = new ArrayList<>();
      private boolean                  sawRecord;



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            sawRecord = true;
            BackupPlanValidator.Result result = BackupPlanValidator.validate(PlanSettings.of(record), null, false, List.of());
            if(result.ok())
            {
               create.incrementCountAndAddPrimaryKey(record.getValueString("planKey"));
               output.getRecords().add(record);
            }
            else
            {
               invalid.incrementCount();
               problems.addAll(result.errors());
            }
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         create.addSelfToListIfAnyCount(lines);
         invalid.setBulletsOfText(new ArrayList<>(problems));
         invalid.addSelfToListIfAnyCount(lines);
         if(!sawRecord)
         {
            lines.add(new ProcessSummaryLine(Status.INFO, null, "A plan with the key " + PLAN_KEY + " already exists, so nothing will be created. Edit that plan instead."));
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Load: insert through the normal insert (validation, owner, schedule).
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine created  = BackupsProcesses.line(Status.OK, "plan will be created", "plans will be created", "plan was created", "plans were created");
      private final ProcessSummaryLine failed   = BackupsProcesses.line(Status.ERROR, "plan could not be created", "plans could not be created", "plan could not be created", "plans could not be created");
      private final List<String>       problems = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         InsertInput insert = new InsertInput(BackupsTables.PLAN);
         insert.setRecords(input.getRecords());
         InsertOutput inserted = new InsertAction().execute(insert);
         for(QRecord record : inserted.getRecords())
         {
            if(record.getErrors() == null || record.getErrors().isEmpty())
            {
               created.incrementCountAndAddPrimaryKey(record.getValue("id"));
               output.getRecords().add(record);
            }
            else
            {
               failed.incrementCount();
               record.getErrors().forEach(error -> problems.add(error.getMessage()));
            }
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         created.addSelfToListIfAnyCount(lines);
         failed.setBulletsOfText(new ArrayList<>(problems));
         failed.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
