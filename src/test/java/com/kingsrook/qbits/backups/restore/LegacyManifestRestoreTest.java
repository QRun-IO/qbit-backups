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


import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.verify.BackupVerifier;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Exercise pre-RC.6 manifest fingerprints through verification and restore.
 ** The schema fixture was frozen from the original stand-in, not recomputed
 ** with the implementation being tested.
 *******************************************************************************/
class LegacyManifestRestoreTest
{
   /*******************************************************************************
    ** Load the blog and grant the permission required by the core restore flag.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(30);
   }



   /*******************************************************************************
    ** Build an old-format manifest around real backup parts and re-seal its hash.
    *******************************************************************************/
   private BackupRunResult legacyBackup() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      ObjectMapper mapper = new ObjectMapper();
      ObjectNode manifest = (ObjectNode) mapper.readTree(BackupsTestUtils.BACKUPS.getBytes(run.getManifestKey()));
      JsonNode legacy;
      try(var in = getClass().getResourceAsStream("/manifests/legacy-schema.json"))
      {
         legacy = mapper.readTree(in);
      }
      for(JsonNode table : manifest.get("tables"))
      {
         ObjectNode entry = (ObjectNode) table;
         JsonNode old = legacy.get(entry.get("name").asText());
         entry.remove("fingerprintAlgorithm");
         entry.set("schema", old.get("schema"));
         entry.set("fingerprint", old.get("fingerprint"));
      }
      byte[] bytes = mapper.writeValueAsBytes(manifest);
      BackupsTestUtils.BACKUPS.putBytes(run.getManifestKey(), bytes);
      new BackupCatalog().update(BackupsTables.RUN, run.getRunId(), Map.of("manifestSha256", HashingOutputStream.sha256Hex(bytes)));
      assertThat(BackupManifest.read(bytes).table("blogPost").fingerprintAlgorithm()).isNull();
      return (run);
   }



   /*******************************************************************************
    ** Old hashes still receive exact codec verification and restore all data,
    ** including original timestamps, after deletes, edits and additions.
    *******************************************************************************/
   @Test
   void testLegacyManifestVerifiesAndRestores() throws Exception
   {
      String before = RestoreTestSupport.contentHash("blogPost");
      BackupRunResult run = legacyBackup();
      var verified = new BackupVerifier().verify(run.getRunId(), BackupVerifier.CONTENT, "MANUAL", "tester");
      assertThat(verified.status()).isEqualTo("PASSED");
      assertThat(verified.messages()).noneMatch(message -> message.contains("has changed since"));
      RestoreTestSupport.mutate();
      RestoreOutcome outcome = new RestoreEngine().execute(RestoreTestSupport.request(run, RestoreRequest.MODE_REPLACE, "blogPost").withConfirmationText("blogPost"));
      assertThat(outcome.getErrors()).isEmpty();
      assertThat(outcome.getTables().get("blogPost").getSchemaStatus()).isEqualTo("MATCH");
      assertThat(RestoreTestSupport.contentHash("blogPost")).isEqualTo(before);
   }



   /*******************************************************************************
    ** Legacy field maps and core field lists also compare correctly when hashes
    ** differ: optional additions are compatible and type changes are rejected.
    *******************************************************************************/
   @Test
   void testLegacySchemaChangesAreAnalyzed() throws Exception
   {
      BackupRunResult run = legacyBackup();
      QContext.getQInstance().getTable("author").withField(new QFieldMetaData("nickname", QFieldType.STRING));
      QContext.getQInstance().getTable("blogPost").getField("title").setType(QFieldType.INTEGER);
      RestoreOutcome review = new RestoreEngine().review(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "author", "blogPost"));
      assertThat(review.getTables().get("author").getSchemaStatus()).isEqualTo("COMPATIBLE");
      assertThat(review.getTables().get("blogPost").getSchemaStatus()).isEqualTo("MISMATCH");
   }
}
