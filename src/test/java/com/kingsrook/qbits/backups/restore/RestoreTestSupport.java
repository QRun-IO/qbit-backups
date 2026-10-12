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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.codec.JsonlRowCodec;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupEngine;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Helpers shared by the restore tests: take a backup, mutate the data the way
 ** the spec's acceptance does (delete 10, edit 10, add 10), and read tables back.
 *******************************************************************************/
final class RestoreTestSupport
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private RestoreTestSupport()
   {
   }



   /*******************************************************************************
    ** Create the nightly plan (with overrides) and run it.
    *******************************************************************************/
   static BackupRunResult backup(Map<String, Serializable> overrides) throws Exception
   {
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("name", "Nightly", "planKey", "nightly", "isActive", true,
         "scopeType", "INSTANCE", "storageBucketAlias", "backups", "slaHours", 26));
      values.putAll(overrides);
      BackupCatalog catalog = new BackupCatalog();
      Integer       planId  = catalog.insert(BackupsTables.PLAN, values);
      BackupRunResult result = new BackupEngine().execute(BackupRunFactory.forPlan(catalog.get(BackupsTables.PLAN, planId).orElseThrow(), "MANUAL"));
      assertThat(result.isUsable()).as(result.getErrorMessage()).isTrue();
      return (result);
   }



   /*******************************************************************************
    ** Back up again with the existing nightly plan.
    *******************************************************************************/
   static BackupRunResult backupAgain() throws Exception
   {
      QRecord plan = new BackupCatalog().where(BackupsTables.PLAN, "planKey", "nightly").get(0);
      BackupRunResult result = new BackupEngine().execute(BackupRunFactory.forPlan(plan, "MANUAL"));
      assertThat(result.isUsable()).as(result.getErrorMessage()).isTrue();
      return (result);
   }



   /*******************************************************************************
    ** The manifest of a run.
    *******************************************************************************/
   static BackupManifest manifest(BackupRunResult result) throws Exception
   {
      return (BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey())));
   }



   /*******************************************************************************
    ** Delete posts 1..10, edit posts 11..20 (title), add posts 31..40.
    *******************************************************************************/
   static void mutate() throws Exception
   {
      DeleteInput delete = new DeleteInput("blogPost");
      List<Serializable> ids = new ArrayList<>();
      for(int i = 1; i <= 10; i++)
      {
         ids.add(i);
      }
      delete.setPrimaryKeys(ids);
      new DeleteAction().execute(delete);

      List<QRecord> edits = new ArrayList<>();
      for(int i = 11; i <= 20; i++)
      {
         edits.add(new QRecord().withValue("id", i).withValue("title", "Edited " + i));
      }
      UpdateInput update = new UpdateInput("blogPost");
      update.setRecords(edits);
      new UpdateAction().execute(update);

      List<Map<String, Serializable>> added = new ArrayList<>();
      for(int i = 31; i <= 40; i++)
      {
         Map<String, Serializable> row = new LinkedHashMap<>();
         row.put("id", i);
         row.put("authorId", 1);
         row.put("title", "Added " + i);
         row.put("createDate", Instant.parse("2026-10-13T00:00:00Z"));
         added.add(row);
      }
      BackupsTestUtils.insert("blogPost", added);
   }



   /*******************************************************************************
    ** All the rows of a table, by id.
    *******************************************************************************/
   static List<QRecord> rows(String table) throws QException
   {
      QueryInput input = new QueryInput(table);
      input.setFilter(new QQueryFilter().withOrderBy(new QFilterOrderBy("id", true)));
      return (new QueryAction().execute(input).getRecords());
   }



   /*******************************************************************************
    ** A SHA-256 over the canonical rows of a table (by id), leaving out the sensitive
    ** fields (they are not backed up) and, until the core restore flag ships, the created
    ** and modified dates (without the flag the standard actions set them to now).
    *******************************************************************************/
   static String contentHash(String table) throws Exception
   {
      QTableMetaData meta = QContext.getQInstance().getTable(table);
      List<String> left = new ArrayList<>();
      meta.getFields().values().stream().filter(f -> f.getIsSensitive()).forEach(f -> left.add(f.getName()));
      if(!new StandardRestoreWriter().usesCoreRestoreFlag())
      {
         left.addAll(List.of("createDate", "modifyDate"));
      }
      JsonlRowCodec codec = new JsonlRowCodec(meta, left);
      StringBuilder sb    = new StringBuilder();
      for(QRecord row : rows(table))
      {
         sb.append(codec.encode(row)).append('\n');
      }
      return (HashingOutputStream.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)));
   }



   /*******************************************************************************
    ** A request over some tables, restoring from a run.
    *******************************************************************************/
   static RestoreRequest request(BackupRunResult run, String mode, String... tables)
   {
      return (new RestoreRequest().withSourceRunId(run.getRunId()).withMode(mode).withTables(List.of(tables)).withRequestedBy("tester"));
   }



   /*******************************************************************************
    ** The summary line whose message contains the text.
    *******************************************************************************/
   static ProcessSummaryLine line(List<ProcessSummaryLineInterface> lines, boolean result, String text)
   {
      for(ProcessSummaryLineInterface line : lines)
      {
         ProcessSummaryLine candidate = (ProcessSummaryLine) line;
         candidate.pickMessage(result);
         if(candidate.getMessage() != null && candidate.getMessage().contains(text))
         {
            return (candidate);
         }
      }
      return (null);
   }



   /*******************************************************************************
    ** Declare a unique key on a table of the fixture.
    *******************************************************************************/
   static void uniqueKey(String table, String... fields)
   {
      QContext.getQInstance().getTable(table).withUniqueKey(new UniqueKey(fields));
   }
}
