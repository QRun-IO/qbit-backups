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

package com.kingsrook.qbits.backups.manifest;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qqq.backend.core.context.QContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Fingerprint algorithm dispatch is explicit; a marker cannot be ignored just
 ** because its hash happens to match a different supported algorithm.
 *******************************************************************************/
class TableFingerprintsTest
{
   /*******************************************************************************
    ** A frozen old hash matches only the legacy algorithm, and new hashes only
    ** match the declared core algorithm. Cosmetic changes preserve both.
    *******************************************************************************/
   @Test
   void testAlgorithmDispatch() throws Exception
   {
      BackupsTestUtils.init();
      var table = QContext.getQInstance().getTable("blogPost");
      ObjectMapper mapper = new ObjectMapper();
      ObjectNode entry;
      try(var in = getClass().getResourceAsStream("/manifests/legacy-schema.json"))
      {
         entry = (ObjectNode) mapper.readTree(in).get("blogPost");
      }
      String oldHash = entry.get("fingerprint").asText();
      String coreHash = TableFingerprints.of(table, "memory");
      assertThat(coreHash).isNotEqualTo(oldHash);
      table.setLabel("Cosmetic label");
      assertThat(TableFingerprints.matches(mapper.treeToValue(entry, BackupManifest.TableEntry.class), table, "memory")).isTrue();
      entry.put("fingerprintAlgorithm", TableFingerprints.LEGACY_ALGORITHM);
      assertThat(TableFingerprints.matches(mapper.treeToValue(entry, BackupManifest.TableEntry.class), table, "memory")).isTrue();
      entry.put("fingerprintAlgorithm", TableFingerprints.CORE_ALGORITHM);
      assertThat(TableFingerprints.matches(mapper.treeToValue(entry, BackupManifest.TableEntry.class), table, "memory")).isFalse();
      entry.put("fingerprint", coreHash);
      assertThat(TableFingerprints.matches(mapper.treeToValue(entry, BackupManifest.TableEntry.class), table, "memory")).isTrue();
      entry.put("fingerprintAlgorithm", "unknown-v2");
      assertThat(TableFingerprints.matches(mapper.treeToValue(entry, BackupManifest.TableEntry.class), table, "memory")).isFalse();
      entry.remove("fingerprint");
      assertThat(TableFingerprints.matches(mapper.treeToValue(entry, BackupManifest.TableEntry.class), table, "memory")).isFalse();
   }
}
