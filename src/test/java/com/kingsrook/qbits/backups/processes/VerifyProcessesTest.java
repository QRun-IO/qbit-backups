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
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Verify backup record action (a streamed ETL with Review and Result lines)
 ** and the scheduled SLA check.
 *******************************************************************************/
class VerifyProcessesTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(7);
   }



   /*******************************************************************************
    ** Both processes are defined on the instance with the right permissions; neither
    ** is in the nav, and the label is plain.
    *******************************************************************************/
   @Test
   void testDefinitions()
   {
      QProcessMetaData verify = QContext.getQInstance().getProcess(VerifyProcesses.VERIFY);
      assertThat(verify).isNotNull();
      assertThat(verify.getLabel()).isEqualTo("Verify backup");
      assertThat(verify.getTableName()).isEqualTo(BackupsTables.RUN);
      assertThat(QContext.getQInstance().getProcess(VerifyProcesses.CHECK_SLA)).isNotNull();
      assertThat(BackupsRuntime.processPermissions()).containsEntry("backupVerify", BackupsPermissions.RUN).containsEntry("backupCheckSla", BackupsPermissions.RUN);
   }



   /*******************************************************************************
    ** The SLA check is scheduled every slaCheckIntervalSeconds when a scheduler is
    ** configured, independent of any backup trigger; otherwise it is unscheduled.
    *******************************************************************************/
   @Test
   void testSlaCheckSchedule()
   {
      assertThat(VerifyProcesses.checkSlaProcess(BackupsRuntime.getConfig()).getSchedule()).isNull();
      assertThat(VerifyProcesses.checkSlaProcess(null).getSchedule()).isNull();

      BackupsQBitConfig scheduled = new BackupsQBitConfig().withBackendName("memory").withSchedulerName("quartz").withSlaCheckIntervalSeconds(120);
      QProcessMetaData  process   = VerifyProcesses.checkSlaProcess(scheduled);
      assertThat(process.getSchedule().getSchedulerName()).isEqualTo("quartz");
      assertThat(process.getSchedule().getRepeatSeconds()).isEqualTo(120);
      assertThat(process.getSchedule().getCronExpression()).isNull();
   }



   /*******************************************************************************
    ** The scheduled step checks every active plan.
    *******************************************************************************/
   @Test
   void testCheckSlaStep() throws Exception
   {
      VerifyTestSupport.backup(VerifyTestSupport.plan(Map.of()));
      VerifyTestSupport.plan(Map.of("name", "Late", "planKey", "late", "storageBucketAlias", "backups"));
      VerifyTestSupport.clock.advance(java.time.Duration.ofHours(30));

      RunProcessInput input = new RunProcessInput();
      input.setProcessName(VerifyProcesses.CHECK_SLA);
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      RunProcessOutput output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      assertThat(output.getValues()).containsEntry("plansChecked", 2).containsEntry("plansNeedingAttention", 2L);
      assertThat(new BackupCatalog().where(BackupsTables.PLAN, "planKey", "late").get(0).getValueString("slaStatus")).isEqualTo("LATE");
   }



   /*******************************************************************************
    ** Verify backup on a good run: Review lists what will be checked without reading
    ** anything; the run passes, and the Result lines link back to the run.
    *******************************************************************************/
   @Test
   void testVerifyGoodRun() throws Exception
   {
      BackupRunResult result = VerifyTestSupport.backup(VerifyTestSupport.plan(Map.of()));
      assertThat(new BackupCatalog().where(BackupsTables.VERIFICATION, "backupRunId", result.getRunId())).isEmpty();

      RunProcessInput  input  = input(result.getRunId());
      RunProcessOutput output = new RunProcessAction().execute(input);
      List<ProcessSummaryLineInterface> review = lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
      assertThat(review).extracting(l -> ((ProcessSummaryLine) l).getStatus()).contains(Status.OK, Status.INFO);
      assertThat(new BackupCatalog().where(BackupsTables.VERIFICATION, "backupRunId", result.getRunId())).as("Review reads nothing and writes nothing").isEmpty();

      input.setStartAfterStep("review");
      output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      List<ProcessSummaryLineInterface> summary = lines(output, StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      ProcessSummaryLine ok = (ProcessSummaryLine) summary.stream().filter(l -> ((ProcessSummaryLine) l).getStatus() == Status.OK).findFirst().orElseThrow();
      assertThat(ok.getCount()).isEqualTo(1);
      assertThat(ok.getPrimaryKeys()).containsExactly(result.getRunId());
      assertThat(new BackupCatalog().where(BackupsTables.VERIFICATION, "backupRunId", result.getRunId())).hasSize(2);
      assertThat(VerifyTestSupport.run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("CONTENT_OK");
   }



   /*******************************************************************************
    ** A corrupted run fails with the first mismatch in the Result line; a run with no
    ** manifest is refused at Review.
    *******************************************************************************/
   @Test
   void testVerifyBadRunsAndRefusals() throws Exception
   {
      BackupRunResult result = VerifyTestSupport.backup(VerifyTestSupport.plan(Map.of()));
      String          key    = VerifyTestSupport.firstPartKey(result, "blogPost");
      byte[]          bytes  = BackupsTestUtils.BACKUPS.getBytes(key).clone();
      bytes[bytes.length / 2] ^= 0x55;
      BackupsTestUtils.BACKUPS.corrupt(key, bytes);

      Integer          noManifest = new BackupCatalog().insert(BackupsTables.RUN, new java.util.LinkedHashMap<>(Map.<String, Serializable>of("runKey", "x1", "status", "FAILED")));
      Integer          pruned     = new BackupCatalog().insert(BackupsTables.RUN, new java.util.LinkedHashMap<>(Map.<String, Serializable>of("runKey", "x2", "status", "SUCCEEDED", "manifestKey", "m", "prunedDate", java.time.Instant.now())));

      RunProcessInput input = input(result.getRunId(), noManifest, pruned);
      input.addValue(VerifyProcesses.FIELD_VERIFY_LEVEL, "STORAGE");
      RunProcessOutput output = new RunProcessAction().execute(input);
      List<ProcessSummaryLineInterface> review = lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
      assertThat(review.stream().filter(l -> ((ProcessSummaryLine) l).getStatus() == Status.ERROR)).hasSize(2);

      input.addValue(VerifyProcesses.FIELD_VERIFY_LEVEL, "CONTENT");
      input.setStartAfterStep("review");
      output = new RunProcessAction().execute(input);
      List<ProcessSummaryLineInterface> summary = lines(output, StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      ProcessSummaryLine failed = (ProcessSummaryLine) summary.stream().filter(l -> ((ProcessSummaryLine) l).getStatus() == Status.ERROR).findFirst().orElseThrow();
      assertThat(failed.getCount()).isEqualTo(1);
      assertThat(failed.getMessageSuffix()).contains("corrupted");
      assertThat(VerifyTestSupport.run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("FAILED");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private RunProcessInput input(Integer... runIds)
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(VerifyProcesses.VERIFY);
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.IN, List.of((Serializable[]) runIds))));
      return (input);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private List<ProcessSummaryLineInterface> lines(RunProcessOutput output, String field)
   {
      return ((List<ProcessSummaryLineInterface>) output.getValues().get(field));
   }
}
