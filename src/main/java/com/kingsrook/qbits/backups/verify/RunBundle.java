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


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.codec.ArtifactTransform;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Everything a verification needs to know about one run, loaded once: the run
 ** row, its storage, its manifest (read and hashed), the transform that undoes
 ** the plan's encryption, and (lazily) the file index.
 *******************************************************************************/
class RunBundle
{
   private static final ObjectMapper MAPPER = new ObjectMapper();

   private final QRecord          run;
   private final QRecord          plan;
   private final BackupStorage    storage;
   private final String           runPrefix;
   private final BackupManifest   manifest;
   private final ArtifactTransform transform;
   private final boolean          encrypted;

   private List<FileIndexEntry> fileIndex;



   /***************************************************************************
    ** One line of the per-run file index.
    **
    ** @param table the referencing table
    ** @param recordKey the referencing record's primary key
    ** @param field the referencing field
    ** @param sha256 the content hash, null when not known
    ** @param size the size in bytes, -1 when not known
    ** @param status COPIED, DEDUPLICATED, MISSING or REFERENCE_ONLY
    ** @param casKey the content-addressed key in the bucket, null when not stored
    ***************************************************************************/
   record FileIndexEntry(String table, String recordKey, String field, String sha256, long size, String status, String casKey)
   {
      /*******************************************************************************
       ** Whether the file is in the file store (so it can be checked).
       *******************************************************************************/
      boolean isStored()
      {
         return (casKey != null && sha256 != null && ("COPIED".equals(status) || "DEDUPLICATED".equals(status)));
      }
   }



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   private RunBundle(QRecord run, QRecord plan, BackupStorage storage, String runPrefix, BackupManifest manifest, ArtifactTransform transform, boolean encrypted)
   {
      this.run = run;
      this.plan = plan;
      this.storage = storage;
      this.runPrefix = runPrefix;
      this.manifest = manifest;
      this.transform = transform;
      this.encrypted = encrypted;
   }



   /*******************************************************************************
    ** Load a run.  Returns null, after recording a mismatch, when the run has no
    ** readable manifest (so it is not usable and there is nothing more to check).
    ** Throws VerifyException when the run cannot be examined at all.
    *******************************************************************************/
   static RunBundle load(QRecord run, QRecord plan, VerifyFindings findings)
   {
      String alias     = run.getValueString("storageBucketAlias");
      String runPrefix = run.getValueString("runPrefix");
      if(alias == null || runPrefix == null)
      {
         throw (new VerifyException("The run has no storage bucket or run prefix recorded"));
      }
      BackupStorage storage = BackupsRuntime.storageFor(alias);

      String manifestKey = run.getValueString("manifestKey") != null ? run.getValueString("manifestKey") : runPrefix + "manifest.json";
      Optional<StoredObjectInfo> head = storage.head(manifestKey);
      if(head.isEmpty())
      {
         findings.mismatch("The manifest is missing from the bucket, so this backup is not usable");
         return (null);
      }

      byte[] bytes;
      try(InputStream in = storage.openRead(manifestKey))
      {
         bytes = in.readAllBytes();
      }
      catch(IOException e)
      {
         throw (new VerifyException("The manifest could not be read: " + e.getMessage(), e));
      }

      String sha = HashingOutputStream.sha256Hex(bytes);
      if(run.getValueString("manifestSha256") != null && !run.getValueString("manifestSha256").equals(sha))
      {
         findings.mismatch("The manifest changed after it was written (its SHA-256 no longer matches the catalog)");
      }

      BackupManifest manifest;
      try
      {
         manifest = BackupManifest.read(bytes);
      }
      catch(IOException e)
      {
         findings.mismatch("The manifest is not valid JSON: " + e.getMessage());
         return (null);
      }
      if(manifest.tables() == null)
      {
         findings.mismatch("The manifest lists no tables section");
         return (null);
      }

      boolean encrypted = manifest.encryption() != null && manifest.encryption().mode() != null && !"NONE".equals(manifest.encryption().mode());
      ArtifactTransform transform = ArtifactTransform.IDENTITY;
      if(encrypted)
      {
         String keyRef = manifest.encryption().keyRef();
         var    config = BackupsRuntime.getConfig();
         if(config == null || config.getArtifactTransformResolver() == null)
         {
            throw (new VerifyException("This backup is encrypted but no decrypt hook is configured (BackupsQBitConfig.withArtifactTransformResolver), so it cannot be verified"));
         }
         transform = config.getArtifactTransformResolver().apply(keyRef);
         if(transform == null)
         {
            throw (new VerifyException("No key is available for the key reference " + keyRef + ", so this backup cannot be decrypted"));
         }
      }
      return (new RunBundle(run, plan, storage, runPrefix, manifest, transform, encrypted));
   }



   /*******************************************************************************
    ** The distinct stored files the run's file index references, in first-seen order
    ** (one entry per hash).  Reads the index once.
    *******************************************************************************/
   synchronized List<FileIndexEntry> distinctStoredFiles(VerifyFindings findings)
   {
      if(fileIndex == null)
      {
         fileIndex = readIndex(findings);
      }
      Map<String, FileIndexEntry> bySha = new LinkedHashMap<>();
      for(FileIndexEntry entry : fileIndex)
      {
         if(entry.isStored())
         {
            bySha.putIfAbsent(entry.sha256(), entry);
         }
      }
      return (new ArrayList<>(bySha.values()));
   }



   /*******************************************************************************
    ** Every index entry, stored or not.
    *******************************************************************************/
   synchronized List<FileIndexEntry> allFileEntries(VerifyFindings findings)
   {
      if(fileIndex == null)
      {
         fileIndex = readIndex(findings);
      }
      return (fileIndex);
   }



   /*******************************************************************************
    ** Read the file index objects.  The index is gzip JSON Lines; it is read as plain
    ** gzip first and, if that is not readable and the plan encrypts, through the
    ** decrypt hook.
    *******************************************************************************/
   private List<FileIndexEntry> readIndex(VerifyFindings findings)
   {
      List<FileIndexEntry> entries = new ArrayList<>();
      if(manifest.files() == null || manifest.files().indexKeys() == null)
      {
         return (entries);
      }
      for(String relative : manifest.files().indexKeys())
      {
         String key = runPrefix + relative;
         if(storage.head(key).isEmpty())
         {
            continue;
         }
         try
         {
            byte[] bytes;
            try(InputStream in = storage.openRead(key))
            {
               bytes = in.readAllBytes();
            }
            try
            {
               parseIndex(new GZIPInputStream(new ByteArrayInputStream(bytes)), entries);
            }
            catch(IOException plainFailed)
            {
               if(!encrypted)
               {
                  throw (plainFailed);
               }
               entries.clear();
               parseIndex(new GZIPInputStream(transform.unwrap(new ByteArrayInputStream(bytes))), entries);
            }
         }
         catch(IOException e)
         {
            findings.mismatch("The file index " + relative + " cannot be read: " + e.getClass().getSimpleName());
         }
      }
      return (entries);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void parseIndex(InputStream in, List<FileIndexEntry> entries) throws IOException
   {
      try(BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
      {
         String line;
         while((line = reader.readLine()) != null)
         {
            if(line.isBlank())
            {
               continue;
            }
            JsonNode node = MAPPER.readTree(line);
            entries.add(new FileIndexEntry(text(node, "table"), text(node, "recordKey"), text(node, "field"), text(node, "sha256"),
               node.hasNonNull("size") ? node.get("size").asLong() : -1L, text(node, "status"), text(node, "casKey")));
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String text(JsonNode node, String name)
   {
      return (node.hasNonNull(name) ? node.get(name).asText() : null);
   }



   /*******************************************************************************
    ** Getter for run
    *******************************************************************************/
   QRecord run()
   {
      return (run);
   }



   /*******************************************************************************
    ** Getter for plan (null for an ad-hoc run)
    *******************************************************************************/
   QRecord plan()
   {
      return (plan);
   }



   /*******************************************************************************
    ** Getter for storage
    *******************************************************************************/
   BackupStorage storage()
   {
      return (storage);
   }



   /*******************************************************************************
    ** Getter for runPrefix
    *******************************************************************************/
   String runPrefix()
   {
      return (runPrefix);
   }



   /*******************************************************************************
    ** Getter for manifest
    *******************************************************************************/
   BackupManifest manifest()
   {
      return (manifest);
   }



   /*******************************************************************************
    ** Getter for transform (the decrypt hook, or the identity)
    *******************************************************************************/
   ArtifactTransform transform()
   {
      return (transform);
   }



   /*******************************************************************************
    ** Whether the run's artifacts are encrypted.
    *******************************************************************************/
   boolean isEncrypted()
   {
      return (encrypted);
   }
}
