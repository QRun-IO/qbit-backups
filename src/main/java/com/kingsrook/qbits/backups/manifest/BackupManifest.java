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


import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;


/*******************************************************************************
 ** The self-describing manifest of a run (spec 8.7), written LAST: it is the
 ** commit marker.  A run without a manifest did not complete.  It holds no
 ** secrets: only key references and fingerprints, never key material.
 **
 ** Unknown properties are ignored when reading, so a newer manifest is still
 ** readable by an older qbit (and by the offline tools).
 *******************************************************************************/
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackupManifest(
   int manifestVersion,
   String runKey,
   PlanInfo plan,
   InstanceInfo instance,
   String startedAt,
   String finishedAt,
   String requestedBy,
   String securityScope,
   ConsistencyInfo consistency,
   EncryptionInfo encryption,
   List<TableEntry> tables,
   List<String> restoreOrder,
   FilesInfo files,
   List<String> warnings,
   Totals totals)
{
   public static final int CURRENT_VERSION = 1;

   private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record PlanInfo(String planKey, String name, Map<String, java.io.Serializable> settings)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record InstanceInfo(String name, String appVersion, String qqqVersion, List<Map<String, String>> qbits)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record ConsistencyInfo(String achieved, List<Map<String, String>> perBackend)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record EncryptionInfo(String mode, String scheme, String keyRef, String keyFingerprint)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record ExcludedField(String name, String reason)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record Part(String key, long rows, long bytesRaw, long bytesStored, String sha256Stored, String sha256Raw, String format, String compression, boolean encrypted)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record TableEntry(
      String name,
      String role,
      String backend,
      String primaryKey,
      String fingerprint,
      String fingerprintAlgorithm,
      Map<String, Object> schema,
      String filter,
      List<ExcludedField> excludedFields,
      long rowCount,
      String rowsHash,
      Map<String, String> keyRange,
      List<Part> parts,
      String consistency)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record FilesInfo(String policy, List<String> indexKeys, long objects, long bytes, long missing)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @JsonIgnoreProperties(ignoreUnknown = true)
   @JsonInclude(JsonInclude.Include.NON_NULL)
   public record Totals(int tables, long rows, long bytesStored)
   {
   }



   /*******************************************************************************
    ** The manifest as pretty JSON bytes.
    *******************************************************************************/
   public byte[] toJsonBytes()
   {
      try
      {
         return (MAPPER.writeValueAsBytes(this));
      }
      catch(IOException e)
      {
         throw (new IllegalStateException("Could not write the manifest", e));
      }
   }



   /*******************************************************************************
    ** Read a manifest.
    *******************************************************************************/
   public static BackupManifest read(InputStream in) throws IOException
   {
      return (MAPPER.readValue(in, BackupManifest.class));
   }



   /*******************************************************************************
    ** Read a manifest from bytes.
    *******************************************************************************/
   public static BackupManifest read(byte[] bytes) throws IOException
   {
      return (MAPPER.readValue(bytes, BackupManifest.class));
   }



   /*******************************************************************************
    ** The table entry with a name, or null.
    *******************************************************************************/
   public TableEntry table(String name)
   {
      return (tables == null ? null : tables.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null));
   }
}
