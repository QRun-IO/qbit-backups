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

package com.kingsrook.qbits.backups.restore;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The restore and download processes, run the way the frontend runs them: the
 ** first screen, Review (the validation summary), Execute and Result.
 *******************************************************************************/
class RestoreProcessTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(30);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   static List<ProcessSummaryLineInterface> lines(RunProcessOutput output, String field)
   {
      return (new ArrayList<>((List<ProcessSummaryLineInterface>) output.getValues().get(field)));
   }



   /*******************************************************************************
    ** Restore... on a backup run: Review shows the per-table lines and a sample diff,
    ** Execute restores, Result shows what was done.
    *******************************************************************************/
   @Test
   void testRestoreProcessEndToEnd() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();

      RunProcessInput input = new RunProcessInput();
      input.setProcessName("backupRestore");
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, run.getRunId())));
      input.addValue("tables", "blogPost");
      input.addValue("mode", "UPSERT");

      RunProcessOutput output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      assertThat(output.getProcessState().getNextStepName()).as("the first screen asks for the options").isPresent().get().isEqualTo("input");
      input.addValue("processUUID", output.getProcessUUID());

      input.setStartAfterStep("input");
      output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      assertThat(output.getProcessState().getNextStepName()).isPresent().get().isEqualTo("review");

      List<ProcessSummaryLineInterface> review = lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
      assertThat(RestoreTestSupport.line(review, false, "records will be inserted into blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(review, false, "records will be updated in blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(review, false, "records are unchanged in blogPost").getCount()).isEqualTo(10);
      ProcessSummaryLine sample = RestoreTestSupport.line(review, false, "changed records in blogPost");
      assertThat(sample.getBulletsOfText()).hasSize(10);
      assertThat(sample.getBulletsOfText().get(0)).contains("title: Edited 11 -> Post 11");
      assertThat(RestoreTestSupport.rows("blogPost")).as("Review writes nothing").hasSize(30);

      input.setStartAfterStep("review");
      output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      assertThat(output.getProcessState().getNextStepName()).isPresent().get().isEqualTo("result");

      List<ProcessSummaryLineInterface> result = lines(output, StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      assertThat(RestoreTestSupport.line(result, true, "records were inserted into blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(result, true, "records were updated in blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(result, true, "A backup of the target tables was taken first")).isNotNull();
      assertThat(result.stream().map(l -> ((ProcessSummaryLine) l).getStatus())).doesNotContain(Status.ERROR);

      assertThat(RestoreTestSupport.rows("blogPost")).hasSize(40);
      assertThat(new BackupCatalog().where(BackupsTables.RESTORE_RUN, "sourceBackupRunId", run.getRunId())).hasSize(1);
   }



   /*******************************************************************************
    ** Restore this table, on a table's data artifact, restores just that table.
    *******************************************************************************/
   @Test
   void testRestoreThisTableOnAnArtifact() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();
      QRecord artifact = new BackupCatalog().where(BackupsTables.ARTIFACT, "backupRunId", run.getRunId()).stream()
         .filter(a -> "blogPost".equals(a.getValueString("tableName"))).findFirst().orElseThrow();

      RunProcessInput input = new RunProcessInput();
      input.setProcessName("backupRestoreTable");
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, artifact.getValueInteger("id"))));
      input.addValue("mode", "INSERT_NEW");

      RunProcessOutput output = new RunProcessAction().execute(input);
      input.addValue("processUUID", output.getProcessUUID());
      input.setStartAfterStep("input");
      output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      assertThat(RestoreTestSupport.line(lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY), false, "records will be inserted into blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY), false, "author")).as("only that table").isNull();

      input.setStartAfterStep("review");
      new RunProcessAction().execute(input);
      assertThat(RestoreTestSupport.rows("blogPost")).hasSize(40);
   }



   /*******************************************************************************
    ** The Review of a REPLACE without the typed confirmation is an error line, and
    ** Execute refuses.
    *******************************************************************************/
   @Test
   void testReplaceWithoutConfirmationIsRefused() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();

      RunProcessInput input = new RunProcessInput();
      input.setProcessName("backupRestore");
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, run.getRunId())));
      input.addValue("tables", "blogPost");
      input.addValue("mode", "REPLACE");

      RunProcessOutput output = new RunProcessAction().execute(input);
      input.addValue("processUUID", output.getProcessUUID());
      input.setStartAfterStep("input");
      output = new RunProcessAction().execute(input);
      ProcessSummaryLine error = RestoreTestSupport.line(lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY), false, "To confirm a REPLACE, type blogPost");
      assertThat(error).isNotNull();
      assertThat(error.getStatus()).isEqualTo(Status.ERROR);

      input.setStartAfterStep("review");
      assertThatThrownBy(() -> new RunProcessAction().execute(input)).hasMessageContaining("To confirm a REPLACE, type blogPost");
      assertThat(RestoreTestSupport.rows("blogPost")).hasSize(30);
   }



   /*******************************************************************************
    ** Download: presigned links for the run's objects (ciphertext as stored), each one
    ** a separate link, with the manifest and checksums among them.
    *******************************************************************************/
   @Test
   void testDownloadIssuesLinks() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      int artifacts = new BackupCatalog().where(BackupsTables.ARTIFACT, "backupRunId", run.getRunId()).size();

      RunProcessInput input = new RunProcessInput();
      input.setProcessName("backupDownload");
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, run.getRunId())));

      RunProcessOutput output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      input.addValue("processUUID", output.getProcessUUID());
      if(output.getProcessState().getNextStepName().orElse("").equals("review"))
      {
         input.setStartAfterStep("review");
      }
      else
      {
         input.setStartAfterStep(output.getProcessState().getNextStepName().orElse(null));
      }

      List<ProcessSummaryLineInterface> review = lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
      assertThat(RestoreTestSupport.line(review, false, "files will get download links").getCount()).isEqualTo(artifacts);

      output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      ProcessSummaryLine issued = RestoreTestSupport.line(lines(output, StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY), true, "download links were issued");
      assertThat(issued.getCount()).isEqualTo(artifacts);
      assertThat(issued.getBulletsOfText()).hasSize(artifacts);
      assertThat(issued.getBulletsOfText()).anyMatch(b -> b.contains("manifest.json") && b.contains("memory://") && b.contains("ttl=900"));
      assertThat(issued.getBulletsOfText()).anyMatch(b -> b.contains("checksums.sha256"));
   }



   /*******************************************************************************
    ** Download on one artifact links that artifact plus the manifest and checksums.
    *******************************************************************************/
   @Test
   void testDownloadOfOneArtifact() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      QRecord artifact = new BackupCatalog().where(BackupsTables.ARTIFACT, "backupRunId", run.getRunId()).stream()
         .filter(a -> "blogPost".equals(a.getValueString("tableName"))).findFirst().orElseThrow();

      RunProcessInput input = new RunProcessInput();
      input.setProcessName("backupDownloadArtifact");
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, artifact.getValueInteger("id"))));
      RunProcessOutput output = new RunProcessAction().execute(input);
      input.addValue("processUUID", output.getProcessUUID());
      input.setStartAfterStep("review");
      output = new RunProcessAction().execute(input);

      ProcessSummaryLine issued = RestoreTestSupport.line(lines(output, StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY), true, "download links were issued");
      assertThat(issued.getCount()).isEqualTo(3);
      assertThat(issued.getBulletsOfText()).anyMatch(b -> b.contains("blogPost-part-00001"));
   }
}
