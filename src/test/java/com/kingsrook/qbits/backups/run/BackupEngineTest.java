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
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


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
      assertThat(posts.fingerprint()).startsWith("sha256:");

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
      BackupRunResult missing = run(new BackupCatalog().where(BackupsTables.PLAN, "planKey", "nightly").get(0));
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
         assertThat(result.getErrorMessage()).contains("injected write failure");
         assertThat(BackupsTestUtils.BACKUPS.list(result.getRunPrefix() + "manifest.json")).isEmpty();
         assertThat(new BackupCatalog().get(BackupsTables.RUN, result.getRunId()).orElseThrow().getValueString("status")).isEqualTo("FAILED");
      }
      finally
      {
         BackupsTestUtils.BACKUPS.withFailWritesFor(null);
      }
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
}
