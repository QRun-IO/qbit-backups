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

package com.kingsrook.qbits.backups.tables;


import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.plan.BackupValidatorReset;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.schedule.BackupPlanVerifySyncToScheduledJobProcess;
import com.kingsrook.qbits.backups.schedule.BackupScheduledVerifyProcess;
import com.kingsrook.qqq.backend.core.actions.processes.QProcessCallbackFactory;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Two half-wired settings of backups_plan, made honest or made to work:
 ** - the settings that are not available yet (read replica, native dump,
 **   incremental, PARQUET, ZSTD) are refused at save with a clear message and say so
 **   in their field help;
 ** - verifyCronExpression creates a scheduled job that runs the process
 **   backupVerifyPlan; that process now exists, and verifies the plan's latest
 **   successful backup through the plan's verifyLevel.
 *******************************************************************************/
class UnavailableSettingsAndScheduledVerifyTest
{
   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(3);
      BackupValidatorReset.reset();
   }



   /*******************************************************************************
    ** Save a plan through the table (validation runs), returning the record.
    *******************************************************************************/
   private QRecord save(Map<String, Serializable> overrides) throws QException
   {
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("name", "P" + System.nanoTime(), "planKey", "p" + System.nanoTime(), "isActive", true, "scopeType", "INSTANCE", "storageBucketAlias", "backups"));
      values.putAll(overrides);
      QRecord record = new QRecord();
      record.setValues(values);
      InsertInput input = new InsertInput(BackupsTables.PLAN);
      input.setRecords(List.of(record));
      input.withPermissionBypass("test");
      return (new InsertAction().execute(input).getRecords().get(0));
   }



   /*******************************************************************************
    ** Every setting that is not available yet is refused, and the field help says so.
    *******************************************************************************/
   @Test
   void unavailableSettingsAreRefusedAndSayWhy() throws Exception
   {
      assertThat(save(Map.of()).getErrors()).as("the defaults are fine").isEmpty();

      Map<String, Map<String, Serializable>> refused = Map.of(
         "Reading from a replica is not available yet", Map.of("useReadReplica", true),
         "Native database dumps are not available yet", Map.of("nativeDump", "PG_DUMP_CUSTOM"),
         "Incremental backups are not available yet", Map.of("incrementalMode", "CHANGED_SINCE_LAST"),
         "PARQUET format is not available yet", Map.of("format", "PARQUET"),
         "ZSTD compression is not available yet", Map.of("compression", "ZSTD"));
      for(Map.Entry<String, Map<String, Serializable>> entry : refused.entrySet())
      {
         QRecord record = save(entry.getValue());
         assertThat(record.getErrors()).as(entry.getValue().toString()).isNotEmpty();
         assertThat(record.getErrors().toString()).contains(entry.getKey());
      }

      var table = QContext.getQInstance().getTable(BackupsTables.PLAN);
      for(String field : List.of("useReadReplica", "nativeDump", "incrementalMode", "format", "compression", "scheduledJobId"))
      {
         assertThat(table.getField(field).getHelpContents()).as(field + " help").isNotEmpty();
         assertThat(table.getField(field).getHelpContents().get(0).getContent()).as(field + " help").matches("(?is).*(not available yet|not used).*");
      }
   }



   /*******************************************************************************
    ** The process a verify cron schedules exists, and verifies the plan's latest
    ** successful run, as the scheduler, through the plan's verifyLevel.
    *******************************************************************************/
   @Test
   void theVerifyCronRunsARealProcess() throws Exception
   {
      assertThat(QContext.getQInstance().getProcess(BackupPlanVerifySyncToScheduledJobProcess.VERIFY_PROCESS_NAME)).as("the process the scheduled verify job names").isNotNull();

      Integer planId = catalog.insert(BackupsTables.PLAN, new LinkedHashMap<>(Map.of("name", "Nightly", "planKey", "nightly", "isActive", true, "scopeType", "INSTANCE",
         "storageBucketAlias", "backups", "verifyLevel", "STORAGE")));
      assertThat(run(planId).getValues().get("status")).as("nothing to verify before the first backup").isEqualTo("NO_BACKUP");

      QRecord plan   = catalog.get(BackupsTables.PLAN, planId).orElseThrow();
      var     result = new BackupRunCoordinator().runPlan(plan, "SCHEDULED");
      assertThat(result.getStatus()).isIn("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS");

      RunProcessOutput output = run(planId);
      assertThat(output.getException()).isEmpty();
      assertThat(output.getValues().get("status")).isEqualTo("PASSED");
      assertThat(output.getValues().get("level")).isEqualTo("STORAGE");

      List<QRecord> verifications = catalog.where(BackupsTables.VERIFICATION, "backupRunId", result.getRunId());
      assertThat(verifications).singleElement().satisfies(v ->
      {
         assertThat(v.getValueString("level")).isEqualTo("STORAGE");
         assertThat(v.getValueString("trigger")).isEqualTo("SCHEDULED");
         assertThat(v.getValueString("requestedByUserId")).isEqualTo(BackupScheduledVerifyProcess.SCHEDULER_USER);
      });
      assertThat(catalog.get(BackupsTables.PLAN, planId).orElseThrow().getValue("lastVerifiedDate")).as("a storage check alone does not count as verified").isNull();

      /////////////////////////////////////////////////////////////////////
      // the plan's level decides how far it goes; an inactive plan is skipped //
      /////////////////////////////////////////////////////////////////////
      catalog.update(BackupsTables.PLAN, planId, Map.of("verifyLevel", "CONTENT"));
      assertThat(run(planId).getValues().get("level")).isEqualTo("CONTENT");
      assertThat(catalog.get(BackupsTables.PLAN, planId).orElseThrow().getValue("lastVerifiedDate")).isNotNull();
      catalog.update(BackupsTables.PLAN, planId, Map.of("isActive", false));
      assertThat(run(planId).getValues().get("status")).isEqualTo("INACTIVE");
   }



   /*******************************************************************************
    ** An unset or unknown level falls back to CONTENT.
    *******************************************************************************/
   @Test
   void levelFallsBackToContent()
   {
      assertThat(BackupScheduledVerifyProcess.levelOf(new QRecord())).isEqualTo("CONTENT");
      assertThat(BackupScheduledVerifyProcess.levelOf(new QRecord().withValue("verifyLevel", "BOGUS"))).isEqualTo("CONTENT");
      assertThat(BackupScheduledVerifyProcess.levelOf(new QRecord().withValue("verifyLevel", "TEST_RESTORE"))).isEqualTo("TEST_RESTORE");
   }



   /*******************************************************************************
    ** Run the scheduled verify entry point for a plan, as the scheduler would.
    *******************************************************************************/
   private RunProcessOutput run(Integer planId) throws QException
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(BackupScheduledVerifyProcess.NAME);
      input.setCallback(QProcessCallbackFactory.forPrimaryKey("id", planId));
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      return (new RunProcessAction().execute(input));
   }
}
