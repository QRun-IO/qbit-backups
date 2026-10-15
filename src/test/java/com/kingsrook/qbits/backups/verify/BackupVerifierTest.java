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

package com.kingsrook.qbits.backups.verify;


import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.alerts.BackupAlerts;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.kingsrook.qbits.backups.VerifyTestSupport.backup;
import static com.kingsrook.qbits.backups.VerifyTestSupport.plan;
import static com.kingsrook.qbits.backups.VerifyTestSupport.reload;
import static com.kingsrook.qbits.backups.VerifyTestSupport.run;
import static com.kingsrook.qbits.backups.VerifyTestSupport.sink;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Verification at every level against the memory backend, including the failure
 ** injection cases: a corrupted part, a deleted file-store object, a wrong
 ** checksum, a missing manifest, and a wrong key.
 *******************************************************************************/
class BackupVerifierTest
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
    ** A good run passes STORAGE then CONTENT; every table, row and file is checked,
    ** the verification rows, the run status and the plan are updated.
    *******************************************************************************/
   @Test
   void testStorageAndContentPass() throws Exception
   {
      QRecord         plan   = plan(Map.of());
      BackupRunResult result = backup(plan);

      List<VerificationOutcome> outcomes = new BackupVerifier().verifyThrough(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "tester");
      assertThat(outcomes).extracting(VerificationOutcome::level).containsExactly("STORAGE", "CONTENT");
      assertThat(outcomes).extracting(VerificationOutcome::status).containsExactly("PASSED", "PASSED");

      VerificationOutcome content = outcomes.get(1);
      assertThat(content.tablesChecked()).isEqualTo(2);
      assertThat(content.rowsChecked()).isEqualTo(10);
      assertThat(content.filesChecked()).isEqualTo(3);
      assertThat(content.mismatches()).isZero();

      List<QRecord> rows = new BackupCatalog().where(BackupsTables.VERIFICATION, "backupRunId", result.getRunId());
      assertThat(rows).hasSize(2);
      QRecord contentRow = rows.stream().filter(r -> "CONTENT".equals(r.getValueString("level"))).findFirst().orElseThrow();
      assertThat(contentRow.getValueString("status")).isEqualTo("PASSED");
      assertThat(contentRow.getValueLong("rowsChecked")).isEqualTo(10L);
      assertThat(contentRow.getValueString("detailJson")).contains("\"table\":\"blogPost\"").contains("\"expectedRows\":7").contains("\"actualRows\":7").contains("\"ok\":true");

      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("CONTENT_OK");
      QRecord reloaded = reload(plan);
      assertThat(reloaded.getValueInteger("lastVerifiedRunId")).isEqualTo(result.getRunId());
      assertThat(reloaded.getValueInstant("lastVerifiedDate")).isEqualTo(VerifyTestSupport.clock.instant());

      assertThat(new BackupCatalog().query(BackupsTables.FILE_OBJECT, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()))
         .allSatisfy(f -> assertThat(f.getValueString("lastVerifyStatus")).isEqualTo("PASSED"));
      assertThat(sink.open).isEmpty();
   }



   /*******************************************************************************
    ** An unknown level is refused; an unknown run is refused.
    *******************************************************************************/
   @Test
   void testBadArguments() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      assertThatThrownBy(() -> new BackupVerifier().verify(result.getRunId(), "WHATEVER", "MANUAL", "t")).hasMessageContaining("Unknown verification level");
      assertThatThrownBy(() -> new BackupVerifier().verify(99999, "STORAGE", "MANUAL", "t")).hasMessageContaining("No backup run");
   }



   /*******************************************************************************
    ** A flipped byte in a part is caught by CONTENT with a clear message, the run is
    ** marked FAILED, and an alert is raised; STORAGE (sizes only) still passes.
    *******************************************************************************/
   @Test
   void testCorruptedPartFailsContent() throws Exception
   {
      QRecord         plan   = plan(Map.of());
      BackupRunResult result = backup(plan);
      String          key    = VerifyTestSupport.firstPartKey(result, "blogPost");
      byte[]          bytes  = BackupsTestUtils.BACKUPS.getBytes(key).clone();
      bytes[bytes.length / 2] ^= 0x55;
      BackupsTestUtils.BACKUPS.corrupt(key, bytes);

      List<VerificationOutcome> outcomes = new BackupVerifier().verifyThrough(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "tester");
      assertThat(outcomes).extracting(VerificationOutcome::status).containsExactly("PASSED", "FAILED");
      assertThat(outcomes.get(1).messages()).anyMatch(m -> m.contains("tables/blogPost/part-00001.jsonl.gz") && (m.contains("corrupted") || m.contains("checksum mismatch")));
      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("FAILED");
      assertThat(reload(plan).getValueInstant("lastVerifiedDate")).isNull();

      String key2 = BackupAlerts.verifyKey("nightly", "CONTENT");
      assertThat(sink.isOpen(key2)).isTrue();
      assertThat(sink.openAlert(key2).severity()).isEqualTo(com.kingsrook.qbits.backups.alerts.BackupAlertSink.Severity.ERROR);
      assertThat(sink.openAlert(key2).type()).isEqualTo("backups.verify.failed");
      assertThat(sink.openAlert(key2).detail()).containsEntry("plan", "nightly").containsEntry("level", "CONTENT").doesNotContainKey("key");

      ///////////////////////////////////////////////////////////////////////////
      // a later verification of a good run of the same plan resolves the alert //
      ///////////////////////////////////////////////////////////////////////////
      BackupRunResult good = backup(plan);
      assertThat(new BackupVerifier().verifyThrough(good.getRunId(), BackupVerifier.CONTENT, "MANUAL", "tester")).extracting(VerificationOutcome::status).containsExactly("PASSED", "PASSED");
      assertThat(sink.isOpen(key2)).isFalse();
   }



   /*******************************************************************************
    ** A part that is valid gzip but whose bytes differ from what was written fails
    ** the stored and content checksums, and the row content hash.
    *******************************************************************************/
   @Test
   void testReplacedPartContentFails() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      String          key    = VerifyTestSupport.firstPartKey(result, "blogPost");
      String          text   = gunzip(BackupsTestUtils.BACKUPS.getBytes(key)).replace("Post 1", "Post X");
      BackupsTestUtils.BACKUPS.putBytes(key, gzip(text));

      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("FAILED");
      assertThat(outcome.messages()).anyMatch(m -> m.startsWith("Stored checksum mismatch")).anyMatch(m -> m.startsWith("Content checksum mismatch")).anyMatch(m -> m.startsWith("Row content hash mismatch"));
   }



   /*******************************************************************************
    ** A truncated part fails the STORAGE size check.
    *******************************************************************************/
   @Test
   void testTruncatedPartFailsStorage() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      String          key    = VerifyTestSupport.firstPartKey(result, "blogPost");
      byte[]          bytes  = BackupsTestUtils.BACKUPS.getBytes(key);
      BackupsTestUtils.BACKUPS.corrupt(key, java.util.Arrays.copyOf(bytes, bytes.length - 5));

      List<VerificationOutcome> outcomes = new BackupVerifier().verifyThrough(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(outcomes).hasSize(1);
      assertThat(outcomes.get(0).status()).isEqualTo("FAILED");
      assertThat(outcomes.get(0).messages()).anyMatch(m -> m.startsWith("Size mismatch for") && m.contains("blogPost"));
      assertThat(sink.isOpen(BackupAlerts.verifyKey("nightly", "STORAGE"))).isTrue();
   }



   /*******************************************************************************
    ** A deleted part is a missing object at STORAGE level.
    *******************************************************************************/
   @Test
   void testDeletedPartFailsStorage() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      BackupsTestUtils.BACKUPS.removeIgnoringLock(VerifyTestSupport.firstPartKey(result, "author"));

      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("FAILED");
      assertThat(outcome.messages()).anyMatch(m -> m.startsWith("Missing object:") && m.contains("table author"));
   }



   /*******************************************************************************
    ** A provider that stores SHA-256 checksums: a stored value that differs from the
    ** manifest is a STORAGE mismatch.
    *******************************************************************************/
   @Test
   void testProviderChecksumMismatchFailsStorage() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      String          key    = VerifyTestSupport.firstPartKey(result, "author");
      byte[]          bytes  = BackupsTestUtils.BACKUPS.getBytes(key);
      try(var out = BackupsTestUtils.BACKUPS.openWrite(key, WriteOptions.create().withMetadata("sha256", "0000")))
      {
         out.write(bytes);
      }
      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("FAILED");
      assertThat(outcome.messages()).anyMatch(m -> m.startsWith("Checksum mismatch for"));
   }



   /*******************************************************************************
    ** A file-store object that was deleted fails STORAGE (head) and CONTENT.
    *******************************************************************************/
   @Test
   void testDeletedFileObjectFails() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      QRecord         file   = new BackupCatalog().query(BackupsTables.FILE_OBJECT, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()).get(0);
      BackupsTestUtils.BACKUPS.removeIgnoringLock(file.getValueString("objectKey"));

      VerificationOutcome storage = new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(storage.status()).isEqualTo("FAILED");
      assertThat(storage.messages()).anyMatch(m -> m.startsWith("File store object missing: sha256 " + file.getValueString("sha256")));

      VerificationOutcome content = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(content.status()).isEqualTo("FAILED");
      assertThat(content.messages()).anyMatch(m -> m.startsWith("File store object missing"));
      assertThat(new BackupCatalog().get(BackupsTables.FILE_OBJECT, file.getValueInteger("id")).orElseThrow().getValueString("lastVerifyStatus")).isEqualTo("FAILED");
   }



   /*******************************************************************************
    ** A file-store object with the right size but the wrong bytes (a wrong checksum)
    ** fails CONTENT only.
    *******************************************************************************/
   @Test
   void testWrongFileChecksumFailsContent() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      QRecord         file   = new BackupCatalog().query(BackupsTables.FILE_OBJECT, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()).get(0);
      byte[]          bytes  = BackupsTestUtils.BACKUPS.getBytes(file.getValueString("objectKey")).clone();
      bytes[0] ^= 0x01;
      BackupsTestUtils.BACKUPS.corrupt(file.getValueString("objectKey"), bytes);

      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t").status()).isEqualTo("PASSED");
      VerificationOutcome content = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(content.status()).isEqualTo("FAILED");
      assertThat(content.messages()).anyMatch(m -> m.contains("has the wrong content") && m.contains(file.getValueString("sha256")));
   }



   /*******************************************************************************
    ** A manifest that is gone: the run is not usable.
    *******************************************************************************/
   @Test
   void testMissingManifest() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      BackupsTestUtils.BACKUPS.removeIgnoringLock(result.getManifestKey());

      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("FAILED");
      assertThat(outcome.messages()).containsExactly("The manifest is missing from the bucket, so this backup is not usable");
   }



   /*******************************************************************************
    ** A manifest that was changed after it was written (its hash no longer matches
    ** the catalog) and one that is not JSON.
    *******************************************************************************/
   @Test
   void testTamperedAndUnreadableManifest() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      byte[]          bytes  = BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey());
      BackupsTestUtils.BACKUPS.putBytes(result.getManifestKey(), (new String(bytes, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8));
      VerificationOutcome tampered = new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(tampered.status()).isEqualTo("FAILED");
      assertThat(tampered.messages()).anyMatch(m -> m.contains("manifest changed after it was written"));

      BackupsTestUtils.BACKUPS.putBytes(result.getManifestKey(), "not json".getBytes(StandardCharsets.UTF_8));
      VerificationOutcome garbage = new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(garbage.status()).isEqualTo("FAILED");
      assertThat(garbage.messages()).anyMatch(m -> m.contains("not valid JSON"));
   }



   /*******************************************************************************
    ** A run with no recorded bucket cannot be examined at all: ERROR, not FAILED.
    *******************************************************************************/
   @Test
   void testUnexaminableRunIsError() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      new BackupCatalog().update(BackupsTables.RUN, result.getRunId(), java.util.Collections.singletonMap("storageBucketAlias", null));
      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("ERROR");
      assertThat(outcome.messages()).anyMatch(m -> m.contains("could not be carried out"));
   }



   /*******************************************************************************
    ** Encrypted runs verify through the decrypt hook; a wrong key fails clearly; no
    ** hook at all is an ERROR that says what to configure.
    *******************************************************************************/
   @Test
   void testEncryptedRun() throws Exception
   {
      QRecord         plan   = plan(Map.of());
      BackupRunResult result = VerifyTestSupport.backupEncrypted(plan, new VerifyTestSupport.XorTransform(0x5A), "kek-1");

      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t").status()).isEqualTo("ERROR");
      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t").messages()).anyMatch(m -> m.contains("no decrypt hook is configured"));

      BackupsRuntime.getConfig().withArtifactTransformResolver(ref -> null);
      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t").messages()).anyMatch(m -> m.contains("No key is available for the key reference kek-1"));

      BackupsRuntime.getConfig().withArtifactTransformResolver(ref -> new VerifyTestSupport.XorTransform(0x11));
      VerificationOutcome wrongKey = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(wrongKey.status()).isEqualTo("FAILED");
      assertThat(wrongKey.messages()).anyMatch(m -> m.contains("corrupted, or the key is wrong"));

      BackupsRuntime.getConfig().withArtifactTransformResolver(ref -> new VerifyTestSupport.XorTransform(0x5A));
      List<VerificationOutcome> good = new BackupVerifier().verifyThrough(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(good).extracting(VerificationOutcome::status).containsExactly("PASSED", "PASSED", "PASSED");
      assertThat(good.get(1).filesChecked()).isEqualTo(3);
      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("TEST_RESTORE_OK");
   }



   /*******************************************************************************
    ** Files verified within fileReverifyDays are skipped; later they are checked again.
    *******************************************************************************/
   @Test
   void testFileReverifyDaysBoundsTheWork() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t").filesChecked()).isEqualTo(3);

      VerificationOutcome again = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(again.filesChecked()).isZero();
      assertThat(again.filesSkipped()).isEqualTo(3);

      VerifyTestSupport.clock.advance(Duration.ofDays(31));
      VerificationOutcome later = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(later.filesChecked()).isEqualTo(3);
      assertThat(later.filesSkipped()).isZero();
   }



   /*******************************************************************************
    ** A cheap STORAGE pass does not clear a FAILED found by CONTENT, and never
    ** downgrades a CONTENT_OK; a later CONTENT pass does clear the failure.
    *******************************************************************************/
   @Test
   void testVerificationStatusRules() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("CONTENT_OK");
      new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("CONTENT_OK");

      String key   = VerifyTestSupport.firstPartKey(result, "blogPost");
      byte[] bytes = BackupsTestUtils.BACKUPS.getBytes(key).clone();
      bytes[bytes.length / 2] ^= 0x55;
      BackupsTestUtils.BACKUPS.corrupt(key, bytes);
      new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("FAILED");
      new BackupVerifier().verify(result.getRunId(), BackupVerifier.STORAGE, "MANUAL", "t");
      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("FAILED");
   }



   /*******************************************************************************
    ** An ad-hoc run (no plan) verifies without touching a plan or raising plan alerts.
    *******************************************************************************/
   @Test
   void testAdHocRunHasNoPlan() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      new BackupCatalog().update(BackupsTables.RUN, result.getRunId(), java.util.Collections.singletonMap("backupPlanId", null));
      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t").status()).isEqualTo("PASSED");
      assertThat(sink.raises).isEmpty();
   }



   /*******************************************************************************
    ** A table that is no longer in the instance is checked as JSON with a warning; a
    ** table whose schema changed is parsed without the exact round trip.
    *******************************************************************************/
   @Test
   void testSchemaDriftIsAWarning() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      QContext.getQInstance().getTable("blogPost").addField(new com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData("added", com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType.STRING));
      QContext.getQInstance().getTables().remove("author");

      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("PASSED_WITH_WARNINGS");
      assertThat(outcome.messages()).anyMatch(m -> m.contains("Table author no longer exists")).anyMatch(m -> m.contains("Table blogPost has changed since this backup"));
      assertThat(outcome.passed()).isTrue();
   }



   /*******************************************************************************
    ** CSV parts are hashed (stored and decoded bytes) but rows are not recounted.
    *******************************************************************************/
   @Test
   void testCsvOnlyPlanIsHashedWithAWarning() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of("format", "CSV")));
      assertThat(result.getStatus()).startsWith("SUCCEEDED");
      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("PASSED_WITH_WARNINGS");
      assertThat(outcome.messages()).anyMatch(m -> m.contains("only CSV parts"));

      BackupsTestUtils.BACKUPS.corrupt(VerifyTestSupport.firstPartKey(result, "author"), new byte[] { 1, 2, 3 });
      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t").status()).isEqualTo("FAILED");
   }



   /*******************************************************************************
    ** A good run test-restores into the memory scratch target: every table matches,
    ** every file is restored and re-hashed, and the scratch tables and files are gone.
    *******************************************************************************/
   @Test
   void testTestRestoreMemory() throws Exception
   {
      QRecord         plan   = plan(Map.of());
      BackupRunResult result = backup(plan);

      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(outcome.messages()).isEmpty();
      assertThat(outcome.status()).isEqualTo("PASSED");
      assertThat(outcome.rowsChecked()).isEqualTo(10);
      assertThat(outcome.filesChecked()).isEqualTo(3);
      assertThat(outcome.scratchTarget()).startsWith("memory:qqq_verify_");

      assertThat(QContext.getQInstance().getTables().keySet()).noneMatch(n -> n.startsWith("qqq_verify_"));
      assertThat(BackupsTestUtils.BACKUPS.list(TestRestoreVerifier.SCRATCH_FILE_PREFIX)).isEmpty();
      QRecord row = new BackupCatalog().where(BackupsTables.VERIFICATION, "level", "TEST_RESTORE").get(0);
      assertThat(row.getValueString("scratchTarget")).startsWith("memory:");
      assertThat(row.getValueString("detailJson")).contains("\"filesRestored\":3");
      assertThat(run(result.getRunId()).getValueString("verificationStatus")).isEqualTo("TEST_RESTORE_OK");
      assertThat(reload(plan).getValueInteger("lastVerifiedRunId")).isEqualTo(result.getRunId());
   }



   /*******************************************************************************
    ** Test restore catches rows that differ from the backed up rows, a missing
    ** table, an unreadable part and a stored file that is not its hash.
    *******************************************************************************/
   @Test
   void testTestRestoreCatchesProblems() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      String          key    = VerifyTestSupport.firstPartKey(result, "blogPost");
      byte[]          good   = BackupsTestUtils.BACKUPS.getBytes(key);

      BackupsTestUtils.BACKUPS.putBytes(key, gzip(gunzip(good).replace("Post 1", "Post X")));
      VerificationOutcome changed = new BackupVerifier().verify(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(changed.status()).isEqualTo("FAILED");
      assertThat(changed.messages()).anyMatch(m -> m.contains("does not reproduce the backed up rows"));
      assertThat(QContext.getQInstance().getTables().keySet()).noneMatch(n -> n.startsWith("qqq_verify_"));

      BackupsTestUtils.BACKUPS.putBytes(key, new byte[] { 1, 2, 3 });
      VerificationOutcome unreadable = new BackupVerifier().verify(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(unreadable.status()).isEqualTo("FAILED");
      assertThat(unreadable.messages()).anyMatch(m -> m.contains("could not be restored"));
      BackupsTestUtils.BACKUPS.putBytes(key, good);

      QRecord file = new BackupCatalog().query(BackupsTables.FILE_OBJECT, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()).get(0);
      BackupsTestUtils.BACKUPS.corrupt(file.getValueString("objectKey"), "different bytes".getBytes(StandardCharsets.UTF_8));
      VerificationOutcome badFile = new BackupVerifier().verify(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(badFile.status()).isEqualTo("FAILED");
      assertThat(badFile.messages()).anyMatch(m -> m.startsWith("Test restore of file sha256")).anyMatch(m -> m.contains("do not resolve to a restored file"));
      assertThat(BackupsTestUtils.BACKUPS.list(TestRestoreVerifier.SCRATCH_FILE_PREFIX)).isEmpty();

      QContext.getQInstance().getTables().remove("author");
      assertThat(new BackupVerifier().verify(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t").messages()).anyMatch(m -> m.contains("Table author no longer exists"));
   }



   /*******************************************************************************
    ** verifyFileSamplePercent 0 restores no files; CSV-only tables are skipped.
    *******************************************************************************/
   @Test
   void testTestRestoreFileSampleAndCsv() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of("verifyFileSamplePercent", 0)));
      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("PASSED");
      assertThat(outcome.filesChecked()).isZero();

      BackupRunResult csv = backup(VerifyTestSupport.plan(Map.of("name", "Csv", "planKey", "csv", "format", "CSV")));
      VerificationOutcome csvOutcome = new BackupVerifier().verify(csv.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(csvOutcome.status()).isEqualTo("PASSED_WITH_WARNINGS");
      assertThat(csvOutcome.messages()).anyMatch(m -> m.contains("only CSV parts and was not test-restored"));
   }



   /*******************************************************************************
    ** The RDBMS_SCHEMA scratch target is a stub: it ends the verification with an
    ** ERROR that says so, rather than passing.
    *******************************************************************************/
   @Test
   void testRdbmsSchemaScratchTargetIsAStub() throws Exception
   {
      BackupsRuntime.getConfig().withScratchTarget(new RdbmsSchemaScratchTarget("scratchdb"));
      BackupRunResult result = backup(plan(Map.of()));

      VerificationOutcome outcome = new BackupVerifier().verify(result.getRunId(), BackupVerifier.TEST_RESTORE, "MANUAL", "t");
      assertThat(outcome.status()).isEqualTo("ERROR");
      assertThat(outcome.messages()).anyMatch(m -> m.contains("RDBMS_SCHEMA scratch target is not implemented yet"));

      ScratchTarget target = BackupsRuntime.getConfig().getScratchTarget();
      assertThat(target.type()).isEqualTo("RDBMS_SCHEMA");
      assertThat(target.describe("2026-A")).isEqualTo("scratchdb:schema=qqq_verify_2026_a");
      target.drop("2026-A");
   }



   /*******************************************************************************
    ** The outcome of a verification that has not passed or failed reads sensibly.
    *******************************************************************************/
   @Test
   void testManifestReadableByTheVerifier() throws Exception
   {
      BackupRunResult result = backup(plan(Map.of()));
      BackupManifest  manifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey()));
      assertThat(manifest.tables()).hasSize(2);
      assertThat(new VerificationOutcome(1, 2, "STORAGE", "ERROR", 0, 0, 1, 0, 0, 0, List.of(), null).passed()).isFalse();
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String gunzip(byte[] bytes) throws Exception
   {
      return (new String(new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes)).readAllBytes(), StandardCharsets.UTF_8));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static byte[] gzip(String text) throws Exception
   {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try(GZIPOutputStream out = new GZIPOutputStream(bytes))
      {
         out.write(text.getBytes(StandardCharsets.UTF_8));
      }
      return (bytes.toByteArray());
   }


   /*******************************************************************************
    ** A verification must propagate a job interrupt, not report it as corruption.
    *******************************************************************************/
   @Test
   void testDurableCancellation() throws Exception
   {
      BackupRunResult run = backup(plan(Map.of()));
      try(var scope = com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope.open(null, () ->
      {
         throw (new com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException(com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException.Reason.CANCEL));
      }))
      {
         org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BackupVerifier().verify(run.getRunId(), BackupVerifier.CONTENT, "MANUAL", "test"))
            .isInstanceOf(com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException.class);
      }
   }

}
