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

package com.kingsrook.qbits.backups.run;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qbits.backups.scope.TableRole;
import com.kingsrook.qbits.backups.scope.TableRoles;
import com.kingsrook.qqq.backend.core.actions.async.AsyncJobCallback;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope;
import com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException;
import com.kingsrook.qqq.backend.core.jobs.progress.JobLogLevel;
import com.kingsrook.qqq.backend.core.jobs.progress.JobPhase;
import com.kingsrook.qqq.backend.core.jobs.progress.JobProgress;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** End to end tests of a backup run on the memory backend: layout, manifest last,
 ** canonical rows, policies, file copy and deduplication, failure handling.
 *******************************************************************************/
class BackupEngineTest
{

   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(7);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private QRecord plan(Map<String, java.io.Serializable> overrides) throws Exception
   {
      Map<String, java.io.Serializable> values = new java.util.LinkedHashMap<>(Map.of("name", "Nightly", "planKey", "nightly", "isActive", true,
         "scopeType", "INSTANCE", "storageBucketAlias", "backups", "slaHours", 26));
      values.putAll(overrides);
      Integer id = new BackupCatalog().insert(BackupsTables.PLAN, values);
      return (new BackupCatalog().get(BackupsTables.PLAN, id).orElseThrow());
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private BackupRunResult run(QRecord plan) throws Exception
   {
      return (new BackupEngine().execute(BackupRunFactory.forPlan(plan, "MANUAL")));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static String gunzip(byte[] bytes) throws IOException
   {
      return (new String(new GZIPInputStream(new ByteArrayInputStream(bytes)).readAllBytes(), StandardCharsets.UTF_8));
   }



   /*******************************************************************************
    ** A nightly run writes parts, checksums and the manifest LAST, and the manifest
    ** matches the rows and the stored bytes.
    *******************************************************************************/
   @Test
   void testRunWritesManifestAndParts() throws Exception
   {
      BackupRunResult result = run(plan(Map.of()));
      assertThat(result.getStatus()).isEqualTo("SUCCEEDED_WITH_WARNINGS");
      assertThat(result.getWarnings()).anyMatch(w -> w.contains("BEST_EFFORT"));
      assertThat(result.isUsable()).isTrue();

      BackupManifest manifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey()));
      assertThat(manifest.runKey()).isEqualTo(result.getRunKey());
      assertThat(manifest.restoreOrder()).containsSubsequence("author", "blogPost");
      assertThat(manifest.table("userSession")).as("ephemeral tables are not backed up").isNull();
      assertThat(manifest.table("backups_plan")).as("the qbit own tables are not backed up").isNull();

      BackupManifest.TableEntry posts = manifest.table("blogPost");
      assertThat(posts.rowCount()).isEqualTo(7);
      assertThat(posts.excludedFields()).extracting(BackupManifest.ExcludedField::name).containsExactly("secretNote");
      assertThat(posts.fingerprintAlgorithm()).isEqualTo(com.kingsrook.qbits.backups.manifest.TableFingerprints.CORE_ALGORITHM);
      assertThat(posts.fingerprint()).isEqualTo("sha256:" + com.kingsrook.qqq.backend.core.model.metadata.tables.TableSchemaFingerprint.of(QContext.getQInstance().getTable("blogPost"), "memory").getHash());
      assertThat(posts.schema()).isEqualTo(com.kingsrook.qqq.backend.core.model.metadata.tables.TableSchemaFingerprint.of(QContext.getQInstance().getTable("blogPost"), "memory").getDescriptor());

      BackupManifest.Part part = posts.parts().get(0);
      byte[] stored = BackupsTestUtils.BACKUPS.getBytes(result.getRunPrefix() + part.key());
      assertThat(com.kingsrook.qbits.backups.codec.HashingOutputStream.sha256Hex(stored)).isEqualTo(part.sha256Stored());
      String text = gunzip(stored);
      assertThat(text.split("\n")).hasSize(7);
      assertThat(text).doesNotContain("do-not-leak").doesNotContain("secretNote");
      assertThat(text.split("\n")[0]).startsWith("{\"authorId\":2,\"body\":\"Body of 1, with a comma\",\"createDate\":\"20").contains(",\"id\":1,\"mediaKey\":\"media/photo-1.jpg\",\"price\":\"13.3400\"");
      assertThat(text).contains("\"price\":\"13.3400\"").contains("\"publishedOn\":\"2026-01-02\"");
      assertThat(com.kingsrook.qbits.backups.codec.HashingOutputStream.sha256Hex(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(part.sha256Raw());

      String checksums = new String(BackupsTestUtils.BACKUPS.getBytes(result.getRunPrefix() + "checksums.sha256"), StandardCharsets.UTF_8);
      assertThat(checksums).contains(part.sha256Stored() + "  " + part.key());

      QRecord run = new BackupCatalog().get(BackupsTables.RUN, result.getRunId()).orElseThrow();
      assertThat(run.getValueString("status")).isEqualTo("SUCCEEDED_WITH_WARNINGS");
      assertThat(run.getValueString("manifestKey")).isEqualTo(result.getManifestKey());
      assertThat(run.getValueLong("rowsTotal")).isEqualTo(10L);
      assertThat(new BackupCatalog().where(BackupsTables.ARTIFACT, "backupRunId", result.getRunId())).hasSizeGreaterThanOrEqualTo(4);

      QRecord plan = new BackupCatalog().where(BackupsTables.PLAN, "planKey", "nightly").get(0);
      assertThat(plan.getValueInteger("lastSuccessRunId")).isEqualTo(result.getRunId());
      assertThat(plan.getValueString("slaStatus")).isEqualTo("OK");
   }



   /*******************************************************************************
    ** Referenced files are copied into the content-addressed store; the second run
    ** copies nothing new (deduplicated), and rows referencing the same bytes share one object.
    *******************************************************************************/
   @Test
   void testFilesAreCopiedThenDeduplicated() throws Exception
   {
      QRecord         plan  = plan(Map.of());
      BackupRunResult first = run(plan);
      assertThat(first.getFilesReferenced()).isEqualTo(4);
      assertThat(first.getFilesCopied()).isEqualTo(3);
      assertThat(first.getFilesDeduplicated()).isEqualTo(1);
      assertThat(first.getFilesMissing()).isZero();

      List<QRecord> fileObjects = new BackupCatalog().query(BackupsTables.FILE_OBJECT, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter());
      assertThat(fileObjects).hasSize(3);
      for(QRecord fileObject : fileObjects)
      {
         byte[] stored = BackupsTestUtils.BACKUPS.getBytes(fileObject.getValueString("objectKey"));
         assertThat(com.kingsrook.qbits.backups.codec.HashingOutputStream.sha256Hex(stored)).isEqualTo(fileObject.getValueString("sha256"));
         assertThat(fileObject.getValueString("objectKey")).contains("/_files/sha256/" + fileObject.getValueString("sha256").substring(0, 2) + "/");
      }

      BackupRunResult second = run(plan);
      assertThat(second.getFilesCopied()).isZero();
      assertThat(second.getFilesDeduplicated()).isEqualTo(4);
      assertThat(new BackupCatalog().query(BackupsTables.FILE_OBJECT, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).hasSize(3);

      String index = gunzip(BackupsTestUtils.BACKUPS.getBytes(first.getRunPrefix() + "files/index-00001.jsonl.gz"));
      assertThat(index.split("\n")).hasSize(4);
      assertThat(index).contains("\"status\":\"COPIED\"").contains("\"status\":\"DEDUPLICATED\"");
   }



   /*******************************************************************************
    ** REFERENCE_ONLY records the key and size without copying; a missing source is
    ** listed and (under WARN) the run ends with warnings.
    *******************************************************************************/
   @Test
   void testReferenceOnlyAndMissingFiles() throws Exception
   {
      BackupRunResult refOnly = run(plan(Map.of("fileObjectPolicy", "REFERENCE_ONLY")));
      assertThat(refOnly.getFilesCopied()).isZero();
      assertThat(new BackupCatalog().query(BackupsTables.FILE_OBJECT, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).isEmpty();
      assertThat(gunzip(BackupsTestUtils.BACKUPS.getBytes(refOnly.getRunPrefix() + "files/index-00001.jsonl.gz"))).contains("REFERENCE_ONLY");

      BackupsTestUtils.MEDIA.removeIgnoringLock("media/photo-1.jpg");
      RecordingProgress progress = new RecordingProgress();
      BackupRunResult missing = new BackupEngine().execute(BackupRunFactory.forPlan(new BackupCatalog().where(BackupsTables.PLAN, "planKey", "nightly").get(0), "MANUAL").withProgress(progress));
      assertThat(progress.logs).anyMatch(l -> l.startsWith("WARN:") && l.contains("missing"));
      assertThat(missing.getFilesMissing()).isGreaterThan(0);
      assertThat(missing.getWarnings()).anyMatch(w -> w.contains("missing"));
      assertThat(missing.getStatus()).isEqualTo("SUCCEEDED_WITH_WARNINGS");
   }



   /*******************************************************************************
    ** A storage failure mid-run leaves the run FAILED with no manifest (so it is
    ** not usable and the checker treats the prefix as debris).
    *******************************************************************************/
   @Test
   void testStorageFailureLeavesNoManifest() throws Exception
   {
      BackupsTestUtils.BACKUPS.withFailWritesFor(key -> key.contains("/tables/blogPost/"));
      try
      {
         BackupRunResult result = run(plan(Map.of()));
         assertThat(result.getStatus()).isEqualTo("FAILED");
         assertThat(result.isUsable()).isFalse();
         assertThat(result.getErrorMessage()).contains("Backup failed");
         assertThat(BackupsTestUtils.BACKUPS.list(result.getRunPrefix() + "manifest.json")).isEmpty();
         assertThat(new BackupCatalog().get(BackupsTables.RUN, result.getRunId()).orElseThrow().getValueString("status")).isEqualTo("FAILED");
      }
      finally
      {
         BackupsTestUtils.BACKUPS.withFailWritesFor(null);
      }
   }



   /*******************************************************************************
    ** A table on a backend with no query interface (a storage-only module, like
    ** qbit-edi's edi_preview_download) is left out of the scope, even when it is
    ** selected by name, and the run succeeds.  Before the fix the reader queried it
    ** and the run FAILED with "Query is not implemented in this module".
    *******************************************************************************/
   @Test
   void testUnqueryableTableIsExcluded() throws Exception
   {
      QInstance qInstance = QContext.getQInstance();
      qInstance.addBackend(new QBackendMetaData().withName("storageOnly").withBackendType(StorageOnlyBackendModule.class));
      qInstance.addTable(new QTableMetaData().withName("previewDownload").withBackendName("storageOnly").withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER)));

      BackupRunResult result = run(plan(Map.of()));
      assertThat(result.getErrorMessage()).isNull();
      assertThat(result.getStatus()).isEqualTo("SUCCEEDED_WITH_WARNINGS");
      BackupManifest manifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey()));
      assertThat(manifest.table("previewDownload")).isNull();
      assertThat(manifest.table("blogPost")).isNotNull();

      assertThat(TableRoles.roleOf(qInstance, qInstance.getTable("previewDownload"))).isEqualTo(TableRole.EXTERNAL);
      assertThat(TableRoles.isQueryable(qInstance, qInstance.getTable("blogPost"))).isTrue();

      ScopeResolver.ResolvedScope scope = new ScopeResolver(qInstance).resolve(PlanSettings.adHoc().with("scopeType", "TABLES").with("includeRoles", "DATA,EXTERNAL"),
         List.of(new ScopeResolver.ScopeRule(true, "TABLE", "previewDownload", null, false, null), new ScopeResolver.ScopeRule(true, "ROLE", "EXTERNAL", null, false, null)));
      assertThat(scope.names()).doesNotContain("previewDownload");
      assertThat(scope.excluded()).contains(new ScopeResolver.ExcludedTable("previewDownload", ScopeResolver.NOT_QUERYABLE));
   }



   /*******************************************************************************
    ** Required snapshots fail before anything is written (no backend can open one yet).
    *******************************************************************************/
   @Test
   void testSnapshotRequiredFailsBeforeWriting() throws Exception
   {
      BackupRunResult result = run(plan(Map.of("consistency", "SNAPSHOT_REQUIRED")));
      assertThat(result.getStatus()).isEqualTo("FAILED");
      assertThat(BackupsTestUtils.BACKUPS.list("")).isEmpty();
   }



   /*******************************************************************************
    ** CSV (and gzip) output, a page size smaller than the table (keyset paging), part
    ** rotation by rows, and the rowsHash staying the same whatever the format.
    *******************************************************************************/
   @Test
   void testCsvAndPaging() throws Exception
   {
      BackupRunResult jsonl = run(plan(Map.of("pageSize", 2)));
      BackupRunResult csv   = run(plan(Map.of("name", "Csv", "planKey", "csv", "format", "CSV", "pageSize", 3)));

      BackupManifest jm = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(jsonl.getManifestKey()));
      BackupManifest cm = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(csv.getManifestKey()));
      assertThat(cm.table("blogPost").rowsHash()).isEqualTo(jm.table("blogPost").rowsHash());
      assertThat(cm.table("blogPost").parts().get(0).key()).endsWith(".csv.gz");

      String text = gunzip(BackupsTestUtils.BACKUPS.getBytes(csv.getRunPrefix() + cm.table("blogPost").parts().get(0).key()));
      List<String> records = com.kingsrook.qbits.backups.codec.CsvRowCodec.splitRecords(text);
      assertThat(records).hasSize(8);
      assertThat(records.get(0)).startsWith("authorId,body,createDate");
      String authors = gunzip(BackupsTestUtils.BACKUPS.getBytes(csv.getRunPrefix() + cm.table("author").parts().get(0).key()));
      assertThat(com.kingsrook.qbits.backups.codec.CsvRowCodec.splitRecords(authors)).hasSize(4);
      assertThat(authors).contains("\\N").contains("\"Line one\nLine two, with \"\"quotes\"\"");
   }



   /*******************************************************************************
    ** Scope: explicit tables with a filter and field exclusions, and SECRET never included.
    *******************************************************************************/
   @Test
   void testScopeRulesFilterAndExclusions() throws Exception
   {
      QRecord plan = plan(Map.of("scopeType", "TABLES"));
      new BackupCatalog().insert(BackupsTables.PLAN_SCOPE, Map.of("backupPlanId", plan.getValueInteger("id"), "action", "INCLUDE", "selectorType", "TABLE",
         "selectorValue", "blogPost", "filterJson", "{\"criteria\":[{\"fieldName\":\"published\",\"operator\":\"EQUALS\",\"values\":[true]}]}",
         "includeAssociatedChildren", false, "fieldExclusions", "body"));
      BackupRunResult result = run(plan);
      BackupManifest  m      = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey()));
      assertThat(m.tables()).extracting(BackupManifest.TableEntry::name).containsExactly("blogPost");
      assertThat(m.table("blogPost").rowCount()).isEqualTo(3);
      assertThat(m.table("blogPost").excludedFields()).extracting(BackupManifest.ExcludedField::name).contains("body", "secretNote");
      assertThat(gunzip(BackupsTestUtils.BACKUPS.getBytes(result.getRunPrefix() + m.table("blogPost").parts().get(0).key()))).doesNotContain("\"body\"");

      ScopeResolver.ResolvedScope scope = new ScopeResolver(QContext.getQInstance()).resolve(PlanSettings.adHoc().with("scopeType", "TABLES"),
         List.of(new ScopeResolver.ScopeRule(true, "TABLE", "backups_plan", null, false, null)));
      assertThat(scope.tables()).isEmpty();
      assertThat(scope.excluded()).extracting(ScopeResolver.ExcludedTable::reason).containsExactly("BACKUPS");
   }


   /*******************************************************************************
    ** A real plan reports work in order, relative artifact names and no row data.
    *******************************************************************************/
   @Test
   void testJobProgress() throws Exception
   {
      RecordingProgress progress = new RecordingProgress();
      BackupRunResult result = new BackupRunCoordinator().runPlan(plan(Map.of("prefix", "private-prefix")), "MANUAL", progress, null);
      assertThat(result.isUsable()).isTrue();
      assertThat(progress.phases).containsExactly("prepare", "tables", "media", "upload");
      assertThat(progress.getSnapshot().getPhases()).extracting(JobPhase::getWeight).containsExactly(2.0, 75.0, 18.0, 5.0);
      assertThat(progress.getSnapshot().getPhases()).extracting(JobPhase::getStatus)
         .containsExactly(JobPhase.Status.DONE, JobPhase.Status.DONE, JobPhase.Status.DONE, JobPhase.Status.DONE);
      assertThat(progress.counts).contains("tables:0/2 tables", "tables:1/2 tables", "tables:2/2 tables", "media:0/4 files", "media:4/4 files");
      assertThat(progress.bytes).isSorted();
      assertThat(progress.bytes.getLast()).isGreaterThan(0);
      assertThat(progress.getSnapshot().getBytesTotal()).isNull();
      assertThat(progress.messages).contains("Backing up author (1 of 2)", "Backing up blogPost (2 of 2)");
      assertThat(progress.logs).anyMatch(l -> l.matches("INFO:author: 3 rows, .* MB in .* s"));
      assertThat(progress.logs).anyMatch(l -> l.matches("INFO:blogPost: 7 rows, .* MB in .* s"));
      assertThat(progress.logs).contains("INFO:Media: 4 files, 3 copied, 1 already stored, 0 missing", "INFO:Wrote checksums.sha256", "INFO:Wrote manifest.json", "INFO:Wrote files/index-00001.jsonl.gz");
      assertThat(progress.logs).anyMatch(l -> l.startsWith("INFO:Wrote tables/blogPost/"));
      for(String warning : result.getWarnings())
      {
         assertThat(progress.logs).contains("WARN:" + warning);
      }
      assertThat(String.join("\n", progress.logs)).doesNotContain("private-prefix", "do-not-leak", "Body of", "media/photo-");
   }



   /*******************************************************************************
    ** Repeated backups report deduplicated media and complete all four phases.
    *******************************************************************************/
   @Test
   void testProgressWithDeduplication() throws Exception
   {
      QRecord plan = plan(Map.of());
      run(plan);
      RecordingProgress progress = new RecordingProgress();
      BackupRunResult result = new BackupEngine().execute(BackupRunFactory.forPlan(plan, "MANUAL").withProgress(progress));
      assertThat(result.isUsable()).isTrue();
      assertThat(progress.phases).containsExactly("prepare", "tables", "media", "upload");
      assertThat(progress.getSnapshot().getPhases()).allMatch(p -> p.getStatus() == JobPhase.Status.DONE);
      assertThat(progress.logs).contains("INFO:Media: 4 files, 0 copied, 4 already stored, 0 missing");
   }



   /*******************************************************************************
    ** Arbitrary provider failures must not put credentials or row values in jobs.
    *******************************************************************************/
   @Test
   void testProgressFailureDoesNotLeakProviderText() throws Exception
   {
      RecordingProgress progress = new RecordingProgress();
      BackupRunRequest request = BackupRunFactory.forPlan(plan(Map.of()), "MANUAL").withProgress(progress)
         .withReader((table, filter, start, size, transaction, sink) ->
         {
            throw (new com.kingsrook.qqq.backend.core.exceptions.QException("https://storage-user:storage-password@host/?secret=storage-secret accessKey=storage-access Detail: Key (email)=(private-row) already exists."));
         });
      BackupRunResult result = new BackupEngine().execute(request);
      assertThat(result.getStatus()).isEqualTo("FAILED");
      assertThat(progress.logs).contains("ERROR:" + result.getErrorMessage());
      assertThat(progress.getSnapshot().getPhases()).anyMatch(p -> p.getStatus() == JobPhase.Status.FAILED);
      assertThat(result.getErrorMessage()).startsWith("Backup failed: QException: https://[REDACTED]@host/");
      assertThat(String.join("\n", progress.logs) + result.getErrorMessage() + result.getErrorDetail()).doesNotContain("storage-user", "storage-password", "storage-secret", "storage-access", "private-row");
   }



   /*******************************************************************************
    ** The job page's cancel check must escape the engine to the durable worker.
    *******************************************************************************/
   @Test
   void testJobCancellationBetweenPages() throws Exception
   {
      BackupRunRequest request = BackupRunFactory.forPlan(plan(Map.of("pageSize", 1)), "MANUAL");
      int[] checks = { 0 };
      try(JobChunkScope ignored = JobChunkScope.open(null, () ->
      {
         if(++checks[0] == 3)
         {
            throw (new JobInterruptedException(JobInterruptedException.Reason.CANCEL));
         }
      }))
      {
         assertThatThrownBy(() -> new BackupEngine().execute(request)).isInstanceOf(JobInterruptedException.class);
      }
      assertThat(new BackupCatalog().where(BackupsTables.RUN, "status", "CANCELLED")).hasSize(1);
   }



   /*******************************************************************************
    ** Storage providers can include endpoint user-info and raw keys in exceptions.
    *******************************************************************************/
   @Test
   void testStorageFailureDoesNotLeakCredentials() throws Exception
   {
      RecordingProgress progress = new RecordingProgress();
      var storage = new com.kingsrook.qbits.backups.storage.InMemoryBackupStorage()
      {
         /*******************************************************************************
          ** Deliberately unsafe provider diagnostics must stay out of job output.
          *******************************************************************************/
         @Override
         public java.io.OutputStream openWrite(String key, com.kingsrook.qbits.backups.storage.WriteOptions options)
         {
            throw (new com.kingsrook.qbits.backups.storage.BackupStorageException(com.kingsrook.qbits.backups.storage.BackupStorageException.Reason.ACCESS_DENIED,
               "https://endpoint-user:endpoint-password@storage accessKey=access-secret secretKey=secret-secret"));
         }
      };
      BackupRunResult result = new BackupEngine().execute(BackupRunFactory.forPlan(plan(Map.of()), "MANUAL").withProgress(progress).withStorage(storage));
      assertThat(result.getStatus()).isEqualTo("FAILED");
      assertThat(progress.logs).contains("ERROR:" + result.getErrorMessage());
      assertThat(String.join("\n", progress.logs) + result.getErrorMessage() + result.getErrorDetail()).doesNotContain("endpoint-user", "endpoint-password", "access-secret", "secret-secret");
   }



   /*******************************************************************************
    ** A failed put records what an operator needs on the run row, with no secrets:
    ** error_message is "Backup failed: Class: message" and error_detail has the
    ** class, the cause chain and the root cause frames; the presigned signature,
    ** the access key id and the URL user-info are redacted, the object key is kept.
    *******************************************************************************/
   @Test
   void testFailedPutRecordsSanitizedMessageAndDetail() throws Exception
   {
      var storage = new com.kingsrook.qbits.backups.storage.InMemoryBackupStorage()
      {
         /*******************************************************************************
          ** A MinIO-style failure with a presigned URL in the message.
          *******************************************************************************/
         @Override
         public java.io.OutputStream openWrite(String key, com.kingsrook.qbits.backups.storage.WriteOptions options)
         {
            throw (new com.kingsrook.qbits.backups.storage.BackupStorageException(com.kingsrook.qbits.backups.storage.BackupStorageException.Reason.ACCESS_DENIED,
               "AccessDenied on PUT https://minio-user:minio-pass@minio.galaxy.lan/kof22-backups/" + key
                  + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20261016%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Signature=deadbeefcafe0123",
               new IOException("XMinioStorageFull: Storage backend has reached its minimum free drive threshold. Authorization: AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/x, Signature=feedface")));
         }
      };
      BackupRunResult result = new BackupEngine().execute(BackupRunFactory.forPlan(plan(Map.of()), "MANUAL").withStorage(storage));
      assertThat(result.getStatus()).isEqualTo("FAILED");

      QRecord run     = new BackupCatalog().get(BackupsTables.RUN, result.getRunId()).orElseThrow();
      String  message = run.getValueString("errorMessage");
      String  detail  = run.getValueString("errorDetail");
      assertThat(message).isEqualTo(result.getErrorMessage());
      assertThat(detail).isEqualTo(result.getErrorDetail());

      assertThat(message).startsWith("Backup failed: BackupStorageException: AccessDenied on PUT https://[REDACTED]@minio.galaxy.lan/kof22-backups/qqq-backups/testapp/nightly/")
         .doesNotContain("see server logs");
      assertThat(detail).startsWith("com.kingsrook.qbits.backups.storage.BackupStorageException: AccessDenied on PUT")
         .contains("\nCaused by: java.io.IOException: XMinioStorageFull: Storage backend has reached its minimum free drive threshold.")
         .contains("X-Amz-Signature=[REDACTED]")
         .contains("Root cause stack (top ")
         .contains("\tat ");
      assertThat(detail.length()).isLessThanOrEqualTo(FailureText.MAX_DETAIL_CHARS);
      assertThat(message + detail).doesNotContain("deadbeefcafe0123", "AKIAIOSFODNN7EXAMPLE", "minio-user", "minio-pass", "feedface");
   }



   /*******************************************************************************
    ** Failed backups must fail the durable chunk, not just add a summary line.
    *******************************************************************************/
   @Test
   void testDurableLoadPropagatesFailure() throws Exception
   {
      var input = new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput();
      input.setRecords(List.of(plan(Map.of())));
      BackupsTestUtils.BACKUPS.withFailWritesFor(key -> key.contains("/tables/"));
      try(JobChunkScope scope = JobChunkScope.open(null).withProgress(new RecordingProgress()))
      {
         assertThatThrownBy(() -> new com.kingsrook.qbits.backups.processes.BackupsProcesses.RunPlanLoad().runOnePage(input,
            new com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput()))
            .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QException.class).hasMessageContaining("Backup failed");
      }
   }



   /*******************************************************************************
    ** Record values at call time: JobProgress's live snapshot is mutable.
    *******************************************************************************/
   static class RecordingProgress extends JobProgress
   {
      final List<String> phases = new ArrayList<>();
      final List<String> counts = new ArrayList<>();
      final List<Long> bytes = new ArrayList<>();
      final List<String> logs = new ArrayList<>();
      final List<String> messages = new ArrayList<>();

      /*******************************************************************************
       ** Use the core's safe standalone destination.
       *******************************************************************************/
      RecordingProgress()
      {
         super((AsyncJobCallback) null);
      }

      /*******************************************************************************
       ** Record transitions.
       *******************************************************************************/
      @Override
      public JobProgress phase(String name)
      {
         phases.add(name);
         return (super.phase(name));
      }

      /*******************************************************************************
       ** Record counts and their units.
       *******************************************************************************/
      @Override
      public JobProgress done(long done)
      {
         counts.add(getSnapshot().getCurrentPhase() + ":" + done + "/" + getSnapshot().getTotal() + " " + getSnapshot().getUnitLabel());
         return (super.done(done));
      }

      /*******************************************************************************
       ** Record byte counts.
       *******************************************************************************/
      @Override
      public JobProgress bytesDone(long done)
      {
         bytes.add(done);
         return (super.bytesDone(done));
      }

      /*******************************************************************************
       ** Capture text before core redaction as well as exercising core publication.
       *******************************************************************************/
      @Override
      public JobProgress log(JobLogLevel level, String message)
      {
         logs.add(level + ":" + message);
         return (super.log(level, message));
      }

      /*******************************************************************************
       ** Record live messages.
       *******************************************************************************/
      @Override
      public JobProgress message(String message)
      {
         messages.add(message);
         return (super.message(message));
      }
   }

}
