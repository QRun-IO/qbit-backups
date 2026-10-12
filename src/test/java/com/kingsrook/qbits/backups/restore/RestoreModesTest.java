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


import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** End to end tests of restore (spec 13): back up 30 posts, then delete 10, edit
 ** 10 and add 10; Review each mode and check the summary lines; restore and check
 ** counts, hashes and files.
 *******************************************************************************/
class RestoreModesTest
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
    ** Review of each mode reports the counts the restore will produce, in the standard
    ** summary lines, and writes nothing.
    *******************************************************************************/
   @Test
   void testReviewEachMode() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();
      assertThat(RestoreTestSupport.rows("blogPost")).hasSize(30);

      RestoreOutcome insertNew = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_INSERT_NEW, "blogPost"));
      RestoreOutcome.TableOutcome t = insertNew.getTables().get("blogPost");
      assertThat(insertNew.getErrors()).isEmpty();
      assertThat(t.getRows()).isEqualTo(30);
      assertThat(t.getInserted()).isEqualTo(10);
      assertThat(t.getUpdated()).isZero();
      assertThat(t.getUnchanged()).isEqualTo(10);
      assertThat(t.getSkipped()).isEqualTo(10);
      assertThat(t.getDeleted()).isZero();
      assertThat(t.getSchemaStatus()).isEqualTo("MATCH");
      List<ProcessSummaryLineInterface> lines = RestoreSummaries.lines(insertNew, false);
      assertThat(RestoreTestSupport.line(lines, false, "records will be inserted into blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(lines, false, "records are unchanged in blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(lines, false, "left alone in blogPost").getCount()).isEqualTo(10);
      assertThat(RestoreTestSupport.line(lines, false, "key sequence")).isNotNull();

      RestoreOutcome upsert = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost"));
      t = upsert.getTables().get("blogPost");
      assertThat(t.getInserted()).isEqualTo(10);
      assertThat(t.getUpdated()).isEqualTo(10);
      assertThat(t.getUnchanged()).isEqualTo(10);
      assertThat(t.getSkipped()).isZero();
      assertThat(t.getDeleted()).isZero();
      lines = RestoreSummaries.lines(upsert, false);
      assertThat(RestoreTestSupport.line(lines, false, "records will be updated in blogPost").getCount()).isEqualTo(10);
      assertThat(t.getSamples()).hasSize(10);
      assertThat(t.getSamples().get(0).changes()).contains("title: Edited 11 -> Post 11");

      RestoreOutcome replace = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_REPLACE, "blogPost").withConfirmationText("blogPost"));
      assertThat(replace.getErrors()).isEmpty();
      t = replace.getTables().get("blogPost");
      assertThat(t.getDeleted()).isEqualTo(10);
      assertThat(t.getInserted()).isEqualTo(10);
      assertThat(t.getUpdated()).isEqualTo(10);
      assertThat(t.getUnchanged()).isEqualTo(10);
      ProcessSummaryLine deleted = RestoreTestSupport.line(RestoreSummaries.lines(replace, false), false, "will be deleted from blogPost (REPLACE)");
      assertThat(deleted.getCount()).isEqualTo(10);

      assertThat(RestoreTestSupport.rows("blogPost")).as("Review writes nothing").hasSize(30);
      assertThat(new BackupCatalog().query(BackupsTables.RESTORE_RUN, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).isEmpty();
   }



   /*******************************************************************************
    ** INSERT_NEW brings back the deleted rows and leaves edits and additions alone.
    *******************************************************************************/
   @Test
   void testExecuteInsertNew() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();

      RestoreOutcome outcome = new RestoreEngine().execute(RestoreTestSupport.request(run, RestoreRequest.MODE_INSERT_NEW, "blogPost"));
      assertThat(outcome.getTables().get("blogPost").getInserted()).isEqualTo(10);

      List<QRecord> rows = RestoreTestSupport.rows("blogPost");
      assertThat(rows).hasSize(40);
      assertThat(rows.get(0).getValueString("title")).isEqualTo("Post 1");
      assertThat(rows.stream().filter(r -> r.getValueInteger("id") == 15).findFirst().orElseThrow().getValueString("title")).isEqualTo("Edited 15");
   }



   /*******************************************************************************
    ** UPSERT rolls edits back and restores deleted rows, keeps additions; the file that
    ** was removed comes back with the right checksum, and a file with different content
    ** is kept (a conflict).
    *******************************************************************************/
   @Test
   void testExecuteUpsertWithFiles() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();
      String original1 = "photo bytes 1";
      BackupsTestUtils.MEDIA.removeIgnoringLock("media/photo-1.jpg");
      BackupsTestUtils.MEDIA.putBytes("media/photo-2.jpg", "tampered".getBytes());

      RestoreOutcome review = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost"));
      RestoreOutcome.TableOutcome rt = review.getTables().get("blogPost");
      assertThat(rt.getFilesToRestore()).isEqualTo(1);
      assertThat(rt.getFilesPresent()).isEqualTo(1);
      assertThat(rt.getFilesConflicting()).isEqualTo(1);
      assertThat(rt.getFilesMissing()).isZero();
      assertThat(RestoreTestSupport.line(RestoreSummaries.lines(review, false), false, "different content for blogPost")).isNotNull();

      RestoreOutcome outcome = new RestoreEngine().execute(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost"));
      RestoreOutcome.TableOutcome t = outcome.getTables().get("blogPost");
      assertThat(t.getInserted()).isEqualTo(10);
      assertThat(t.getUpdated()).isEqualTo(10);
      assertThat(t.getFilesRestored()).isEqualTo(1);
      assertThat(outcome.getFilesFailed()).isZero();

      List<QRecord> rows = RestoreTestSupport.rows("blogPost");
      assertThat(rows).hasSize(40);
      assertThat(rows.stream().filter(r -> r.getValueInteger("id") == 15).findFirst().orElseThrow().getValueString("title")).isEqualTo("Post 15");

      byte[] restored = BackupsTestUtils.MEDIA.getBytes("media/photo-1.jpg");
      assertThat(new String(restored)).isEqualTo(original1);
      assertThat(HashingOutputStream.sha256Hex(restored)).isEqualTo(HashingOutputStream.sha256Hex(original1.getBytes()));
      assertThat(new String(BackupsTestUtils.MEDIA.getBytes("media/photo-2.jpg"))).as("a conflicting file is kept").isEqualTo("tampered");
      assertThat(BackupsTestUtils.MEDIA.list("media/").stream().filter(o -> o.key().contains("qqq-restore"))).as("no temporary object is left").isEmpty();
   }



   /*******************************************************************************
    ** REPLACE needs the typed confirmation, deletes what is not in the backup, takes a
    ** pre-restore backup first, brings the table back to exactly the backed up rows
    ** (same hash), overwrites a conflicting file, and records the run rows.
    *******************************************************************************/
   @Test
   void testExecuteReplace() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      String originalHash = RestoreTestSupport.contentHash("blogPost");
      RestoreTestSupport.mutate();
      assertThat(RestoreTestSupport.contentHash("blogPost")).isNotEqualTo(originalHash);
      BackupsTestUtils.MEDIA.putBytes("media/photo-2.jpg", "tampered".getBytes());

      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_REPLACE, "blogPost");
      assertThatThrownBy(() -> new RestoreEngine().execute(request)).hasMessageContaining("type blogPost");
      assertThat(RestoreTestSupport.rows("blogPost")).hasSize(30);

      RestoreOutcome outcome = new RestoreEngine().execute(request.withConfirmationText("blogPost"));
      RestoreOutcome.TableOutcome t = outcome.getTables().get("blogPost");
      assertThat(t.getDeleted()).isEqualTo(10);
      assertThat(t.getInserted()).isEqualTo(10);
      assertThat(t.getUpdated()).isEqualTo(10);

      assertThat(RestoreTestSupport.rows("blogPost")).hasSize(30);
      assertThat(RestoreTestSupport.contentHash("blogPost")).as("the rows are exactly the backed up rows again").isEqualTo(originalHash);
      assertThat(new String(BackupsTestUtils.MEDIA.getBytes("media/photo-2.jpg"))).as("REPLACE overwrites a conflicting file").isEqualTo("photo bytes 2");

      QRecord restoreRun = new BackupCatalog().get(BackupsTables.RESTORE_RUN, outcome.getRestoreRunId()).orElseThrow();
      assertThat(restoreRun.getValueString("status")).isIn("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS");
      assertThat(restoreRun.getValueString("mode")).isEqualTo("REPLACE");
      assertThat(restoreRun.getValueLong("rowsDeleted")).isEqualTo(10L);
      assertThat(restoreRun.getValueLong("rowsInserted")).isEqualTo(10L);
      assertThat(restoreRun.getValueInteger("filesRestored")).isEqualTo(1);
      assertThat(restoreRun.getValueString("confirmationText")).isEqualTo("blogPost");
      assertThat(restoreRun.getValueInteger("preRestoreBackupRunId")).isNotNull();
      QRecord pre = new BackupCatalog().get(BackupsTables.RUN, restoreRun.getValueInteger("preRestoreBackupRunId")).orElseThrow();
      assertThat(pre.getValueString("trigger")).isEqualTo("PRE_RESTORE");
      assertThat(pre.getValueString("status")).startsWith("SUCCEEDED");
      List<QRecord> tableRows = new BackupCatalog().where(BackupsTables.RESTORE_RUN_TABLE, "backupRestoreRunId", outcome.getRestoreRunId());
      assertThat(tableRows).hasSize(1);
      assertThat(tableRows.get(0).getValueString("tableName")).isEqualTo("blogPost");
      assertThat(tableRows.get(0).getValueLong("rowsDeleted")).isEqualTo(10L);
      assertThat(tableRows.get(0).getValueString("schemaStatus")).isEqualTo("MATCH");
   }



   /*******************************************************************************
    ** Turning the pre-restore backup off is allowed and is recorded on the restore run.
    *******************************************************************************/
   @Test
   void testPreRestoreBackupCanBeTurnedOff() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();
      RestoreOutcome outcome = new RestoreEngine().execute(RestoreTestSupport.request(run, RestoreRequest.MODE_INSERT_NEW, "blogPost").withPreRestoreBackup(false));
      QRecord restoreRun = new BackupCatalog().get(BackupsTables.RESTORE_RUN, outcome.getRestoreRunId()).orElseThrow();
      assertThat(restoreRun.getValueInteger("preRestoreBackupRunId")).isNull();
      assertThat(restoreRun.getValueString("scopeSummary")).contains("no pre-restore backup");
   }



   /*******************************************************************************
    ** REMAP_NEW_IDS lets the target assign ids and rewrites the references of later
    ** tables through the map; the id map is cleared when the restore ends.
    *******************************************************************************/
   @Test
   void testRemapNewIds() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());

      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_INSERT_NEW, "author", "blogPost").withIdStrategy(RestoreRequest.IDS_REMAP);
      RestoreOutcome review = new RestoreEngine().review(request);
      assertThat(review.getTables().get("author").getInserted()).isEqualTo(3);
      assertThat(review.getTables().get("blogPost").getInserted()).isEqualTo(30);
      assertThat(review.getTables().get("blogPost").getOrphans()).as("the parents are in the backup").isZero();

      RestoreOutcome outcome = new RestoreEngine().execute(request);
      assertThat(outcome.getTables().get("author").getInserted()).isEqualTo(3);

      List<QRecord> authors = RestoreTestSupport.rows("author");
      assertThat(authors).hasSize(6);
      List<QRecord> posts = RestoreTestSupport.rows("blogPost");
      assertThat(posts).hasSize(60);
      QRecord newPost1 = posts.stream().filter(p -> p.getValueInteger("id") > 30 && "Post 1".equals(p.getValueString("title"))).findFirst().orElseThrow();
      QRecord newAuthor = authors.stream().filter(a -> a.getValueInteger("id") == newPost1.getValueInteger("authorId")).findFirst().orElseThrow();
      assertThat(newAuthor.getValueInteger("id")).as("the post points at a new author").isGreaterThan(3);
      assertThat(newAuthor.getValueString("name")).as("old author 2 was Grace").isEqualTo("Grace");
      assertThat(new BackupCatalog().query(BackupsTables.ID_MAP, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).isEmpty();
      assertThat(new BackupCatalog().get(BackupsTables.RESTORE_RUN, outcome.getRestoreRunId()).orElseThrow().getValueString("idStrategy")).isEqualTo("REMAP_NEW_IDS");
   }



   /*******************************************************************************
    ** REMAP with a unique key reuses rows that already exist (the target ids differ),
    ** and the later tables point at the existing parents.
    *******************************************************************************/
   @Test
   void testRemapMatchingOnAUniqueKey() throws Exception
   {
      RestoreTestSupport.uniqueKey("author", "name");
      BackupRunResult run = RestoreTestSupport.backup(Map.of());

      com.kingsrook.qqq.backend.core.actions.tables.DeleteAction delete = new com.kingsrook.qqq.backend.core.actions.tables.DeleteAction();
      for(String table : List.of("blogPost", "author"))
      {
         com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput input = new com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput(table);
         List<Serializable> ids = new ArrayList<>();
         for(QRecord row : RestoreTestSupport.rows(table))
         {
            ids.add(row.getValue("id"));
         }
         input.setPrimaryKeys(ids);
         delete.execute(input);
      }
      BackupsTestUtils.insert("author", List.of(Map.of("id", 10, "name", "Linus"), Map.of("id", 20, "name", "Ada", "bio", "other bio"), Map.of("id", 30, "name", "Grace", "bio", "")));

      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "author", "blogPost").withIdStrategy(RestoreRequest.IDS_REMAP).withMatchOn("name");
      RestoreOutcome review = new RestoreEngine().review(request);
      assertThat(review.getTables().get("author").getInserted()).isZero();
      assertThat(review.getTables().get("author").getUpdated()).as("only Ada's bio differs").isEqualTo(1);
      assertThat(review.getTables().get("author").getUnchanged()).isEqualTo(2);

      new RestoreEngine().execute(request);
      assertThat(RestoreTestSupport.rows("author")).hasSize(3);
      assertThat(RestoreTestSupport.rows("author").stream().filter(a -> a.getValueInteger("id") == 20).findFirst().orElseThrow().getValueString("bio"))
         .startsWith("Line one");
      List<QRecord> posts = RestoreTestSupport.rows("blogPost");
      assertThat(posts).hasSize(30);
      QRecord post1 = posts.stream().filter(p -> "Post 1".equals(p.getValueString("title"))).findFirst().orElseThrow();
      assertThat(post1.getValueInteger("authorId")).as("Post 1 belonged to Grace, who is now id 30").isEqualTo(30);
   }



   /*******************************************************************************
    ** Unique-key conflicts and orphaned references are counted in Review.
    *******************************************************************************/
   @Test
   void testConflictsAndOrphans() throws Exception
   {
      RestoreTestSupport.uniqueKey("author", "name");
      BackupRunResult run = RestoreTestSupport.backup(Map.of());

      com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput input = new com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput("author");
      input.setPrimaryKeys(List.of(1, 2));
      new com.kingsrook.qqq.backend.core.actions.tables.DeleteAction().execute(input);
      BackupsTestUtils.insert("author", List.of(Map.of("id", 5, "name", "Ada")));

      RestoreOutcome authors = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_INSERT_NEW, "author"));
      RestoreOutcome.TableOutcome a = authors.getTables().get("author");
      assertThat(a.getConflicts()).as("Ada id 1 collides with the existing Ada id 5").isEqualTo(1);
      assertThat(a.getInserted()).as("Grace id 2 can come back").isEqualTo(1);
      assertThat(a.getUnchanged()).isEqualTo(1);
      assertThat(RestoreTestSupport.line(RestoreSummaries.lines(authors, false), false, "conflicts on a unique key")).isNotNull();

      RestoreOutcome posts = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_INSERT_NEW, "blogPost"));
      assertThat(posts.getTables().get("blogPost").getOrphans()).as("posts of author 1 and 2: those ids are in neither the backup selection nor the target").isEqualTo(20);
      assertThat(RestoreTestSupport.line(RestoreSummaries.lines(posts, false), false, "reference a parent that is missing")).isNotNull();
   }



   /*******************************************************************************
    ** The schema is compared with the backup:  extra and removed fields are compatible
    ** (the removed ones are skipped); a changed type blocks that table.
    *******************************************************************************/
   @Test
   void testSchemaComparison() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();

      com.kingsrook.qqq.backend.core.context.QContext.getQInstance().getTable("author").getFields().remove("bio");
      com.kingsrook.qqq.backend.core.context.QContext.getQInstance().getTable("blogPost").getField("price").setType(QFieldType.STRING);

      RestoreOutcome review = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "author", "blogPost"));
      RestoreOutcome.TableOutcome author = review.getTables().get("author");
      assertThat(author.getSchemaStatus()).isEqualTo("COMPATIBLE");
      assertThat(author.getUnmappedFields()).containsExactly("bio");
      assertThat(RestoreTestSupport.line(RestoreSummaries.lines(review, false), false, "field bio is in the backup but not in the target and will be skipped")).isNotNull();

      RestoreOutcome.TableOutcome posts = review.getTables().get("blogPost");
      assertThat(posts.getSchemaStatus()).isEqualTo("MISMATCH");
      assertThat(posts.getStatus()).isEqualTo("SKIPPED");
      assertThat(posts.getInserted()).isZero();
      assertThat(RestoreTestSupport.line(RestoreSummaries.lines(review, false), false, "changed type from DECIMAL to STRING").getStatus())
         .isEqualTo(com.kingsrook.qqq.backend.core.model.actions.processes.Status.ERROR);

      RestoreOutcome outcome = new RestoreEngine().execute(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "author", "blogPost"));
      assertThat(outcome.getTables().get("blogPost").getStatus()).isEqualTo("SKIPPED");
      assertThat(RestoreTestSupport.rows("blogPost")).as("the incompatible table is left alone").hasSize(30);
      List<QRecord> tableRows = new BackupCatalog().where(BackupsTables.RESTORE_RUN_TABLE, "backupRestoreRunId", outcome.getRestoreRunId());
      assertThat(tableRows).extracting(r -> r.getValueString("schemaStatus")).containsExactlyInAnyOrder("COMPATIBLE", "MISMATCH");
   }



   /*******************************************************************************
    ** Sensitive values are masked in the sample diff, even when they are in the backup.
    *******************************************************************************/
   @Test
   void testSensitiveValuesAreNeverShown() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of("sensitiveFieldPolicy", "INCLUDE"));
      UpdateInput update = new UpdateInput("blogPost");
      update.setRecords(List.of(new QRecord().withValue("id", 1).withValue("secretNote", "brand-new-secret")));
      new UpdateAction().execute(update);

      RestoreOutcome review = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost"));
      RestoreOutcome.TableOutcome t = review.getTables().get("blogPost");
      assertThat(t.getUpdated()).isEqualTo(1);
      assertThat(t.getSamples()).hasSize(1);
      assertThat(t.getSamples().get(0).changes()).containsExactly("secretNote: ******** -> ********");

      String everything = RestoreSummaries.lines(review, false).stream().map(l -> String.valueOf(((ProcessSummaryLine) l).getBulletsOfText())).reduce("", String::concat);
      assertThat(everything).doesNotContain("brand-new-secret").doesNotContain("do-not-leak");
   }



   /*******************************************************************************
    ** Per table filters restrict the restore to selected records.
    *******************************************************************************/
   @Test
   void testFilteredRestore() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();

      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost")
         .withFilter("blogPost", "{\"criteria\":[{\"fieldName\":\"id\",\"operator\":\"IN\",\"values\":[2,3,12]}]}");
      RestoreOutcome outcome = new RestoreEngine().execute(request);
      RestoreOutcome.TableOutcome t = outcome.getTables().get("blogPost");
      assertThat(t.getRows()).isEqualTo(3);
      assertThat(t.getInserted()).isEqualTo(2);
      assertThat(t.getUpdated()).isEqualTo(1);
      assertThat(RestoreTestSupport.rows("blogPost")).hasSize(32);
   }



   /*******************************************************************************
    ** Writes go through the RestoreWriter in page transactions.
    *******************************************************************************/
   @Test
   void testWritesUseThePluggableWriterInPageTransactions() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();

      List<String> calls = new ArrayList<>();
      StandardRestoreWriter standard = new StandardRestoreWriter();
      RestoreWriter recording = new RestoreWriter()
      {
         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public List<QRecord> insert(com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData table, List<QRecord> records, RestoreRequest options, QBackendTransaction tx)
            throws com.kingsrook.qqq.backend.core.exceptions.QException
         {
            calls.add("insert:" + records.size() + ":" + (tx != null));
            return (standard.insert(table, records, options, tx));
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public List<QRecord> update(com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData table, List<QRecord> records, RestoreRequest options, QBackendTransaction tx)
            throws com.kingsrook.qqq.backend.core.exceptions.QException
         {
            calls.add("update:" + records.size() + ":" + (tx != null));
            return (standard.update(table, records, options, tx));
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public long delete(com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData table, List<Serializable> primaryKeys, RestoreRequest options, QBackendTransaction tx)
            throws com.kingsrook.qqq.backend.core.exceptions.QException
         {
            return (standard.delete(table, primaryKeys, options, tx));
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public boolean usesCoreRestoreFlag()
         {
            return (standard.usesCoreRestoreFlag());
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public boolean canResyncSequences()
         {
            return (true);
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public List<String> resyncSequences(java.util.Collection<com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData> tables)
            throws com.kingsrook.qqq.backend.core.exceptions.QException
         {
            tables.forEach(t -> calls.add("resync:" + t.getName()));
            return (tables.stream().map(com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData::getName).toList());
         }
      };

      RestoreOutcome outcome = new RestoreEngine().execute(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost").withWriter(recording).withPageSize(8));
      assertThat(calls).contains("insert:8:true", "insert:2:true", "update:6:true", "update:4:true");
      assertThat(calls).filteredOn(c -> !c.startsWith("resync")).allMatch(c -> c.endsWith(":true"));
      assertThat(calls).as("the key sequence of the table that got its original ids is resynced, after the writes").endsWith("resync:blogPost");
      assertThat(outcome.getSequencesResynced()).isEqualTo(1);
      assertThat(new BackupCatalog().get(BackupsTables.RESTORE_RUN, outcome.getRestoreRunId()).orElseThrow().getValueInteger("sequencesResynced")).isEqualTo(1);
   }
}
