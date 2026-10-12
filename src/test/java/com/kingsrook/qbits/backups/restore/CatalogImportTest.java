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


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Import backups from storage (spec 9.6): the database with the catalog is gone,
 ** a fresh instance imports the catalog from the manifests in storage and restores
 ** from it, files included.
 *******************************************************************************/
class CatalogImportTest
{
   private String                originalPostsHash;
   private BackupManifest        originalManifest;
   private Map<String, byte[]>   snapshot;



   /*******************************************************************************
    ** Back up an instance, keep what is in the backup bucket, then start over: a fresh
    ** instance (empty tables, empty catalog, empty media) that only has the bucket.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(30);
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      originalPostsHash = RestoreTestSupport.contentHash("blogPost");
      originalManifest = RestoreTestSupport.manifest(run);

      snapshot = new LinkedHashMap<>();
      for(StoredObjectInfo info : BackupsTestUtils.BACKUPS.list(""))
      {
         snapshot.put(info.key(), BackupsTestUtils.BACKUPS.getBytes(info.key()));
      }

      BackupsTestUtils.init();
      snapshot.forEach(BackupsTestUtils.BACKUPS::putBytes);
      assertThat(new BackupCatalog().query(BackupsTables.RUN, new QQueryFilter())).as("a fresh catalog").isEmpty();
      assertThat(RestoreTestSupport.rows("blogPost")).isEmpty();
   }



   /*******************************************************************************
    ** The manifests are found, validated and imported (trigger IMPORTED), the content
    ** addressed files are registered again, and importing twice changes nothing.
    *******************************************************************************/
   @Test
   void testImportRebuildsTheCatalog() throws Exception
   {
      CatalogImporter importer = new CatalogImporter();
      List<String>    keys     = importer.findManifestKeys(BackupsTestUtils.BACKUPS, "qqq-backups/");
      assertThat(keys).hasSize(1);

      CatalogImporter.Inspection inspection = importer.inspect(BackupsTestUtils.BACKUPS, keys.get(0));
      assertThat(inspection.status()).isEqualTo(CatalogImporter.Inspection.NEW);
      assertThat(inspection.manifestSha256()).isEqualTo(HashingOutputStream.sha256Hex(BackupsTestUtils.BACKUPS.getBytes(keys.get(0))));

      Integer runId = importer.importRun("backups", BackupsTestUtils.BACKUPS, inspection);
      QRecord run   = new BackupCatalog().get(BackupsTables.RUN, runId).orElseThrow();
      assertThat(run.getValueString("trigger")).isEqualTo("IMPORTED");
      assertThat(run.getValueString("runKey")).isEqualTo(originalManifest.runKey());
      assertThat(run.getValueString("status")).isEqualTo("SUCCEEDED_WITH_WARNINGS");
      assertThat(run.getValueString("manifestKey")).isEqualTo(keys.get(0));
      assertThat(run.getValueString("manifestSha256")).isEqualTo(inspection.manifestSha256());
      assertThat(run.getValueString("storageBucketAlias")).isEqualTo("backups");
      assertThat(run.getValueString("instanceName")).isEqualTo("testapp");
      assertThat(run.getValueLong("rowsTotal")).isEqualTo(originalManifest.totals().rows());
      assertThat(run.getValueInteger("tablesPlanned")).isEqualTo(originalManifest.tables().size());
      assertThat(run.getValueString("verificationStatus")).isEqualTo("UNVERIFIED");

      List<QRecord> artifacts = new BackupCatalog().where(BackupsTables.ARTIFACT, "backupRunId", runId);
      long parts = originalManifest.tables().stream().mapToLong(t -> t.parts().size()).sum();
      assertThat(artifacts).hasSize((int) parts + 3);
      assertThat(artifacts).extracting(a -> a.getValueString("artifactType")).contains("MANIFEST", "CHECKSUMS", "FILE_OBJECTS_INDEX", "TABLE_DATA");
      QRecord posts = artifacts.stream().filter(a -> "blogPost".equals(a.getValueString("tableName"))).findFirst().orElseThrow();
      assertThat(posts.getValueString("sha256Stored")).isEqualTo(originalManifest.table("blogPost").parts().get(0).sha256Stored());
      assertThat(posts.getValueString("rowsHash")).isEqualTo(originalManifest.table("blogPost").rowsHash());

      assertThat(new BackupCatalog().query(BackupsTables.FILE_OBJECT, new QQueryFilter())).as("the file store is registered again").hasSize(3);

      assertThat(importer.inspect(BackupsTestUtils.BACKUPS, keys.get(0)).status()).isEqualTo(CatalogImporter.Inspection.EXISTS);
      assertThat(new BackupCatalog().query(BackupsTables.RUN, new QQueryFilter())).hasSize(1);
   }



   /*******************************************************************************
    ** The point of the catalog import: restore from the imported run into the fresh
    ** instance and get the original rows (same hash) and the files (same checksum).
    *******************************************************************************/
   @Test
   void testRestoreFromTheImportedCatalog() throws Exception
   {
      CatalogImporter importer = new CatalogImporter();
      CatalogImporter.Inspection inspection = importer.inspect(BackupsTestUtils.BACKUPS, importer.findManifestKeys(BackupsTestUtils.BACKUPS, "qqq-backups/").get(0));
      Integer runId = importer.importRun("backups", BackupsTestUtils.BACKUPS, inspection);

      RestoreRequest request = new RestoreRequest().withSourceRunId(runId).withMode(RestoreRequest.MODE_INSERT_NEW).withRequestedBy("tester");
      RestoreOutcome review = new RestoreEngine().review(request);
      assertThat(review.getErrors()).isEmpty();
      assertThat(review.getTables().get("author").getInserted()).isEqualTo(3);
      assertThat(review.getTables().get("blogPost").getInserted()).isEqualTo(30);
      assertThat(review.getTables().get("blogPost").getFilesToRestore()).isEqualTo(3);

      RestoreOutcome outcome = new RestoreEngine().execute(request);
      assertThat(outcome.getTables().get("blogPost").getInserted()).isEqualTo(30);
      assertThat(outcome.getTables().get("blogPost").getFilesRestored()).isEqualTo(3);
      assertThat(outcome.getFilesFailed()).isZero();

      assertThat(RestoreTestSupport.rows("author")).hasSize(3);
      assertThat(RestoreTestSupport.contentHash("blogPost")).isEqualTo(originalPostsHash);
      for(int i = 0; i < 3; i++)
      {
         assertThat(new String(BackupsTestUtils.MEDIA.getBytes("media/photo-" + i + ".jpg"))).isEqualTo("photo bytes " + i);
      }
      assertThat(new BackupCatalog().where(BackupsTables.RESTORE_RUN, "sourceBackupRunId", runId)).hasSize(1);
   }



   /*******************************************************************************
    ** A manifest whose parts are damaged is reported, not imported.
    *******************************************************************************/
   @Test
   void testDamagedBackupsAreNotImported() throws Exception
   {
      CatalogImporter importer = new CatalogImporter();
      String key = importer.findManifestKeys(BackupsTestUtils.BACKUPS, "qqq-backups/").get(0);
      String partKey = key.substring(0, key.length() - "manifest.json".length()) + originalManifest.table("blogPost").parts().get(0).key();
      BackupsTestUtils.BACKUPS.corrupt(partKey, new byte[] { 1, 2, 3 });

      CatalogImporter.Inspection inspection = importer.inspect(BackupsTestUtils.BACKUPS, key);
      assertThat(inspection.status()).isEqualTo(CatalogImporter.Inspection.INVALID);
      assertThat(inspection.problems()).anyMatch(p -> p.contains("wrong size"));
      assertThat(new BackupCatalog().query(BackupsTables.RUN, new QQueryFilter())).isEmpty();

      BackupsTestUtils.BACKUPS.putBytes(key, "not a manifest".getBytes());
      assertThat(importer.inspect(BackupsTestUtils.BACKUPS, key).problems()).anyMatch(p -> p.contains("cannot be read"));
   }



   /*******************************************************************************
    ** Restore refuses a part that does not match its stored checksum.
    *******************************************************************************/
   @Test
   void testRestoreRefusesADamagedPart() throws Exception
   {
      CatalogImporter importer = new CatalogImporter();
      String key = importer.findManifestKeys(BackupsTestUtils.BACKUPS, "qqq-backups/").get(0);
      Integer runId = importer.importRun("backups", BackupsTestUtils.BACKUPS, importer.inspect(BackupsTestUtils.BACKUPS, key));

      String partKey = key.substring(0, key.length() - "manifest.json".length()) + originalManifest.table("author").parts().get(0).key();
      byte[] same = BackupsTestUtils.BACKUPS.getBytes(partKey).clone();
      same[same.length / 2] ^= 0x55;
      BackupsTestUtils.BACKUPS.corrupt(partKey, same);

      RestoreRequest request = new RestoreRequest().withSourceRunId(runId).withTables(List.of("author")).withMode(RestoreRequest.MODE_INSERT_NEW).withPreRestoreBackup(false);
      assertThatThrownBy(() -> new RestoreEngine().execute(request)).hasMessageContaining("Could not read");
      assertThat(RestoreTestSupport.rows("author")).isEmpty();
   }



   /*******************************************************************************
    ** The import process: Review lists what will be added, Result says what was.
    *******************************************************************************/
   @Test
   void testImportProcess() throws Exception
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName("backupImportCatalog");
      input.addValue("bucketAlias", "backups");
      input.addValue("prefix", "qqq-backups/");

      RunProcessOutput output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      assertThat(output.getProcessState().getNextStepName()).isPresent().get().isEqualTo("input");
      input.addValue("processUUID", output.getProcessUUID());

      input.setStartAfterStep("input");
      output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      List<ProcessSummaryLineInterface> review = RestoreProcessTest.lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
      assertThat(RestoreTestSupport.line(review, false, "backup will be added to the catalog").getCount()).isEqualTo(1);
      assertThat(new BackupCatalog().query(BackupsTables.RUN, new QQueryFilter())).as("Review writes nothing").isEmpty();

      input.setStartAfterStep("review");
      output = new RunProcessAction().execute(input);
      assertThat(output.getException()).isEmpty();
      List<ProcessSummaryLineInterface> result = RestoreProcessTest.lines(output, StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      ProcessSummaryLine imported = RestoreTestSupport.line(result, true, "backup was added to the catalog");
      assertThat(imported.getCount()).isEqualTo(1);
      assertThat(imported.getPrimaryKeys()).hasSize(1);
      assertThat(new BackupCatalog().query(BackupsTables.RUN, new QQueryFilter())).hasSize(1);

      RunProcessInput again = new RunProcessInput();
      again.setProcessName("backupImportCatalog");
      again.addValue("bucketAlias", "backups");
      again.addValue("prefix", "qqq-backups/");
      output = new RunProcessAction().execute(again);
      again.addValue("processUUID", output.getProcessUUID());
      again.setStartAfterStep("input");
      output = new RunProcessAction().execute(again);
      List<ProcessSummaryLineInterface> second = RestoreProcessTest.lines(output, StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);
      assertThat(RestoreTestSupport.line(second, false, "already in the catalog").getCount()).isEqualTo(1);
      assertThat(RestoreTestSupport.line(second, false, "will be added")).isNull();
      assertThat(second.stream().map(l -> ((ProcessSummaryLine) l).getStatus())).doesNotContain(Status.ERROR);
   }
}
