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

package com.kingsrook.qbits.backups;


import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.scope.BackupTableMetaData;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.InMemoryBackupStorage;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;


/*******************************************************************************
 ** Test fixtures: an instance on the memory backend with the Backups qbit and a
 ** small blog app (authors, posts with a media file key, an ephemeral table).
 *******************************************************************************/
public final class BackupsTestUtils
{
   public static final String BACKEND = "memory";

   public static final InMemoryBackupStorage BACKUPS = new InMemoryBackupStorage();
   public static final InMemoryBackupStorage MEDIA   = new InMemoryBackupStorage();



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsTestUtils()
   {
   }



   /*******************************************************************************
    ** Build the instance, install the qbit, and initialize the context.
    *******************************************************************************/
   public static QInstance init() throws Exception
   {
      BackupsRuntime.reset();
      MemoryRecordStore.getInstance().reset();
      BACKUPS.clear();
      MEDIA.clear();

      QInstance qInstance = new QInstance();
      qInstance.withInstanceDefaultAuthentication(new com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData().withType(com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType.FULLY_ANONYMOUS).withName("anon"));
      qInstance.addBackend(new QBackendMetaData().withName(BACKEND).withBackendType(MemoryBackendModule.class));

      qInstance.addTable(new QTableMetaData().withName("author").withBackendName(BACKEND).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("name", QFieldType.STRING))
         .withField(new QFieldMetaData("bio", QFieldType.TEXT)));

      qInstance.addTable(new QTableMetaData().withName("blogPost").withBackendName(BACKEND).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("authorId", QFieldType.INTEGER).withPossibleValueSourceName("author"))
         .withField(new QFieldMetaData("title", QFieldType.STRING))
         .withField(new QFieldMetaData("body", QFieldType.TEXT))
         .withField(new QFieldMetaData("price", QFieldType.DECIMAL))
         .withField(new QFieldMetaData("published", QFieldType.BOOLEAN))
         .withField(new QFieldMetaData("publishedOn", QFieldType.DATE))
         .withField(new QFieldMetaData("createDate", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("mediaKey", QFieldType.STRING))
         .withField(new QFieldMetaData("secretNote", QFieldType.STRING).withIsSensitive(true))
         .withSupplementalMetaData(new BackupTableMetaData().withFileReferenceFields(List.of("mediaKey")).withFileBucketAlias("media")));
      qInstance.addPossibleValueSource(com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource.newForTable("author"));

      qInstance.addTable(new QTableMetaData().withName("userSession").withBackendName(BACKEND).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("token", QFieldType.STRING)));

      Map<String, BackupStorage> buckets = Map.of("backups", BACKUPS, "media", MEDIA);
      BackupsQBitConfig config = new BackupsQBitConfig().withBackendName(BACKEND).withInstanceName("testapp").withStorageResolver(buckets::get).withOpenWhenAnonymous(true);
      new BackupsQBitProducer().withQBitConfig(config).withNamespace("backups").produce(qInstance);

      new QInstanceValidator().validate(qInstance);
      QContext.init(qInstance, new QSession().withPermissions("backups.restore"));
      return (qInstance);
   }



   /*******************************************************************************
    ** Insert rows into a table.
    *******************************************************************************/
   public static void insert(String table, List<Map<String, Serializable>> rows) throws QException
   {
      InsertInput input = new InsertInput(table);
      input.setRecords(rows.stream().map(r ->
      {
         QRecord record = new QRecord();
         record.setValues(new LinkedHashMap<>(r));
         return (record);
      }).toList());
      new InsertAction().execute(input);
   }



   /*******************************************************************************
    ** The standard blog data: 3 authors, and the posts, some with media.
    *******************************************************************************/
   public static void loadBlog(int posts) throws Exception
   {
      insert("author", List.of(
         Map.of("id", 1, "name", "Ada", "bio", "Line one\nLine two, with \"quotes\" and unicode é中"),
         Map.of("id", 2, "name", "Grace", "bio", ""),
         Map.of("id", 3, "name", "Linus")));
      List<Map<String, Serializable>> rows = new java.util.ArrayList<>();
      for(int i = 1; i <= posts; i++)
      {
         Map<String, Serializable> row = new LinkedHashMap<>();
         row.put("id", i);
         row.put("authorId", 1 + (i % 3));
         row.put("title", "Post " + i);
         row.put("body", "Body of " + i + ", with a comma");
         row.put("price", new BigDecimal("12.3400").add(BigDecimal.valueOf(i)));
         row.put("published", i % 2 == 0);
         row.put("publishedOn", java.time.LocalDate.of(2026, 1, 1).plusDays(i));
         row.put("createDate", Instant.parse("2026-10-12T02:00:00.123456Z").plusSeconds(i));
         row.put("secretNote", "do-not-leak-" + i);
         if(i % 2 == 1)
         {
            String key = "media/photo-" + (i % 3) + ".jpg";
            row.put("mediaKey", key);
            if(MEDIA.getBytes(key) == null)
            {
               MEDIA.putBytes(key, ("photo bytes " + (i % 3)).getBytes());
            }
         }
         rows.add(row);
      }
      insert("blogPost", rows);
      insert("userSession", List.of(Map.of("id", 1, "token", "tok")));
   }
}
