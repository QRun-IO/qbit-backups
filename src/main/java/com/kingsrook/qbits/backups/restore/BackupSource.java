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


import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.codec.ArtifactTransform;
import com.kingsrook.qbits.backups.codec.CsvRowCodec;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.codec.JsonlRowCodec;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** A backup run opened for restore: the catalog row, its manifest (checked against
 ** the manifest hash in the catalog), its storage, and readers for the rows and the
 ** file index.  Every part is checked against the stored SHA-256 in the manifest as
 ** it is read, so a damaged part fails the restore instead of restoring bad rows.
 *******************************************************************************/
public final class BackupSource
{
   private static final ObjectMapper MAPPER = new ObjectMapper();

   /////////////////////////////////////////////////////////////////////////////////
   // Supplies the transform that decrypts the parts of an encrypted run.  The    //
   // default knows no keys, so restoring an encrypted run fails with a clear     //
   // message until the crypto package (QQQ core PR 1107) provider is wired in.   //
   /////////////////////////////////////////////////////////////////////////////////
   private static volatile Function<BackupManifest.EncryptionInfo, ArtifactTransform> transformResolver = null;

   private final QRecord          run;
   private final BackupStorage    storage;
   private final String           runPrefix;
   private final BackupManifest   manifest;
   private final ArtifactTransform transform;



   /*******************************************************************************
    **
    *******************************************************************************/
   private BackupSource(QRecord run, BackupStorage storage, String runPrefix, BackupManifest manifest, ArtifactTransform transform)
   {
      this.run = run;
      this.storage = storage;
      this.runPrefix = runPrefix;
      this.manifest = manifest;
      this.transform = transform;
   }



   /*******************************************************************************
    ** Install the resolver for the decrypting transform (null removes it).
    *******************************************************************************/
   public static void setTransformResolver(Function<BackupManifest.EncryptionInfo, ArtifactTransform> resolver)
   {
      transformResolver = resolver;
   }



   /*******************************************************************************
    ** Open a run for restore.
    *******************************************************************************/
   public static BackupSource open(Integer runId) throws QException
   {
      QRecord run = new BackupCatalog().get(BackupsTables.RUN, runId).orElseThrow(() -> new QException("The backup run " + runId + " was not found"));
      String  status = run.getValueString("status");
      if(!"SUCCEEDED".equals(status) && !"SUCCEEDED_WITH_WARNINGS".equals(status))
      {
         throw (new QException("The backup run " + run.getValueString("runKey") + " did not complete (status " + status + "), so it cannot be restored"));
      }
      String manifestKey = run.getValueString("manifestKey");
      if(manifestKey == null)
      {
         throw (new QException("The backup run " + run.getValueString("runKey") + " has no manifest"));
      }

      BackupStorage storage = BackupsRuntime.storageFor(run.getValueString("storageBucketAlias"));
      try
      {
         byte[] bytes = readAll(storage.openRead(manifestKey));
         String expected = run.getValueString("manifestSha256");
         if(expected != null && !expected.equals(HashingOutputStream.sha256Hex(bytes)))
         {
            throw (new QException("The manifest of backup run " + run.getValueString("runKey") + " does not match its recorded checksum"));
         }
         BackupManifest manifest = BackupManifest.read(bytes);
         String         runPrefix = run.getValueString("runPrefix");
         if(runPrefix == null)
         {
            runPrefix = manifestKey.substring(0, manifestKey.length() - "manifest.json".length());
         }
         return (new BackupSource(run, storage, runPrefix, manifest, transformFor(manifest)));
      }
      catch(IOException | RuntimeException e)
      {
         throw (new QException("Could not read the manifest of backup run " + run.getValueString("runKey") + ": " + e.getMessage(), e));
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static ArtifactTransform transformFor(BackupManifest manifest) throws QException
   {
      BackupManifest.EncryptionInfo encryption = manifest.encryption();
      if(encryption == null || encryption.mode() == null || "NONE".equals(encryption.mode()))
      {
         return (ArtifactTransform.IDENTITY);
      }
      Function<BackupManifest.EncryptionInfo, ArtifactTransform> resolver = transformResolver;
      ArtifactTransform resolved = resolver == null ? null : resolver.apply(encryption);
      if(resolved == null)
      {
         throw (new QException("This backup is encrypted (key " + encryption.keyRef() + "), and no key provider is available to restore it"));
      }
      return (resolved);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static byte[] readAll(InputStream in) throws IOException
   {
      try(in)
      {
         return (in.readAllBytes());
      }
   }



   /*******************************************************************************
    ** The catalog row of the run.
    *******************************************************************************/
   public QRecord getRun()
   {
      return (run);
   }



   /*******************************************************************************
    ** The manifest.
    *******************************************************************************/
   public BackupManifest getManifest()
   {
      return (manifest);
   }



   /*******************************************************************************
    ** The storage the backup lives in.
    *******************************************************************************/
   public BackupStorage getStorage()
   {
      return (storage);
   }



   /*******************************************************************************
    ** The transform that decrypts the stored parts (identity when not encrypted).
    *******************************************************************************/
   public ArtifactTransform getTransform()
   {
      return (transform);
   }



   /*******************************************************************************
    ** The prefix of the run's objects.
    *******************************************************************************/
   public String getRunPrefix()
   {
      return (runPrefix);
   }



   /*******************************************************************************
    ** Stream the rows of one table, decoding each with the target table's field types.
    ** The sink may throw an unchecked exception to stop.
    *******************************************************************************/
   public void readRows(BackupManifest.TableEntry entry, QTableMetaData decodeTable, Consumer<QRecord> sink) throws QException
   {
      List<BackupManifest.Part> parts = new ArrayList<>();
      for(BackupManifest.Part part : entry.parts())
      {
         if("JSONL".equals(part.format()))
         {
            parts.add(part);
         }
      }
      if(parts.isEmpty())
      {
         parts.addAll(entry.parts());
      }

      for(BackupManifest.Part part : parts)
      {
         try
         {
            readPart(part, decodeTable, sink);
         }
         catch(IOException e)
         {
            throw (new QException("Could not read " + part.key() + " of the backup: " + e.getMessage(), e));
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void readPart(BackupManifest.Part part, QTableMetaData decodeTable, Consumer<QRecord> sink) throws IOException, QException
   {
      MessageDigest digest = HashingOutputStream.newSha256();
      try(DigestInputStream stored = new DigestInputStream(storage.openRead(runPrefix + part.key()), digest))
      {
         InputStream plain = transform.unwrap(stored);
         InputStream text  = "GZIP".equals(part.compression()) ? new GZIPInputStream(plain) : plain;

         if("CSV".equals(part.format()))
         {
            String       all     = new String(text.readAllBytes(), StandardCharsets.UTF_8);
            List<String> records = CsvRowCodec.splitRecords(all);
            if(!records.isEmpty())
            {
               CsvRowCodec  codec  = new CsvRowCodec(decodeTable, null);
               List<String> header = CsvRowCodec.splitCells(records.get(0));
               for(int i = 1; i < records.size(); i++)
               {
                  sink.accept(codec.toRecord(CsvRowCodec.splitCells(records.get(i)), header));
               }
            }
         }
         else
         {
            JsonlRowCodec codec = new JsonlRowCodec(decodeTable, null);
            BufferedReader reader = new BufferedReader(new InputStreamReader(text, StandardCharsets.UTF_8));
            String line;
            while((line = reader.readLine()) != null)
            {
               if(!line.isEmpty())
               {
                  sink.accept(codec.decode(line));
               }
            }
         }

         stored.transferTo(OutputStream.nullOutputStream());
      }

      String actual = HashingOutputStream.hex(digest.digest());
      if(part.sha256Stored() != null && !part.sha256Stored().equals(actual))
      {
         throw (new IOException("checksum mismatch (the stored part is damaged or was changed)"));
      }
   }



   /*******************************************************************************
    ** The file index entries of the given tables, keyed by table, record key and field.
    *******************************************************************************/
   public Map<String, JsonNode> fileIndex(Set<String> tables) throws QException
   {
      Map<String, JsonNode> index = new HashMap<>();
      if(manifest.files() == null || manifest.files().indexKeys() == null)
      {
         return (index);
      }
      for(String indexKey : manifest.files().indexKeys())
      {
         try(InputStream raw = storage.openRead(runPrefix + indexKey);
             BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(transform.unwrap(raw)), StandardCharsets.UTF_8)))
         {
            String line;
            while((line = reader.readLine()) != null)
            {
               if(line.isEmpty())
               {
                  continue;
               }
               JsonNode node = MAPPER.readTree(new ByteArrayInputStream(line.getBytes(StandardCharsets.UTF_8)));
               if(tables.contains(node.path("table").asText()))
               {
                  index.put(indexKeyOf(node.path("table").asText(), node.path("recordKey").asText(), node.path("field").asText()), node);
               }
            }
         }
         catch(IOException e)
         {
            throw (new QException("Could not read the file index " + indexKey + ": " + e.getMessage(), e));
         }
      }
      return (index);
   }



   /*******************************************************************************
    ** The lookup key of a file index entry.
    *******************************************************************************/
   public static String indexKeyOf(String table, String recordKey, String field)
   {
      return (table + "\u0001" + recordKey + "\u0001" + field);
   }
}
