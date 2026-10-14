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


import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.TableSchemaFingerprint;


/*******************************************************************************
 ** Core schema fingerprints with read compatibility for pre-RC.6 manifests.
 *******************************************************************************/
public final class TableFingerprints
{
   public static final String CORE_ALGORITHM = "qqq-table-schema-v1";
   public static final String LEGACY_ALGORITHM = "qbit-backups-table-v1";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private TableFingerprints()
   {
   }



   /*******************************************************************************
    ** The core descriptor stored in new manifests.
    *******************************************************************************/
   public static Map<String, Object> descriptor(QTableMetaData table, String backendType)
   {
      return (TableSchemaFingerprint.of(table, backendType).getDescriptor());
   }



   /*******************************************************************************
    ** Retain the manifest's sha256 prefix around the core hash.
    *******************************************************************************/
   public static String of(QTableMetaData table, String backendType)
   {
      return ("sha256:" + TableSchemaFingerprint.of(table, backendType).getHash());
   }



   /*******************************************************************************
    ** An absent algorithm identifies manifests written before RC.6. Unknown
    ** algorithms must not silently fall back to a different fingerprint.
    *******************************************************************************/
   public static boolean matches(BackupManifest.TableEntry entry, QTableMetaData table, String backendType)
   {
      if(entry.fingerprint() == null)
      {
         return (false);
      }
      String algorithm = entry.fingerprintAlgorithm();
      if(algorithm == null || LEGACY_ALGORITHM.equals(algorithm))
      {
         return (entry.fingerprint().equals(LegacyTableFingerprint.of(table, backendType)));
      }
      return (CORE_ALGORITHM.equals(algorithm) && entry.fingerprint().equals(of(table, backendType)));
   }



   /*******************************************************************************
    ** Normalize both descriptor versions to field-name maps for restore's
    ** compatibility analysis. Core fields use a list and explicit flag names.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   public static Map<String, Object> fields(Map<String, Object> descriptor)
   {
      Object fields = descriptor == null ? null : descriptor.get("fields");
      if(fields instanceof Map<?, ?> map)
      {
         return ((Map<String, Object>) map);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      if(fields instanceof List<?> list)
      {
         for(Object item : list)
         {
            Map<String, Object> field = (Map<String, Object>) item;
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("type", field.get("type"));
            normalized.put("required", field.get("isRequired"));
            normalized.put("maxLength", field.get("maxLength"));
            normalized.put("pvs", field.get("possibleValueSource"));
            normalized.put("heavy", field.get("isHeavy"));
            normalized.put("sensitive", field.get("isSensitive"));
            result.put((String) field.get("name"), normalized);
         }
      }
      return (result);
   }



   /*******************************************************************************
    ** The instance fingerprint remains a hash of the ordered table fingerprints.
    *******************************************************************************/
   public static String combine(List<String> tableFingerprints)
   {
      return ("sha256:" + HashingOutputStream.sha256Hex(String.join("\n", tableFingerprints).getBytes(StandardCharsets.UTF_8)));
   }
}
