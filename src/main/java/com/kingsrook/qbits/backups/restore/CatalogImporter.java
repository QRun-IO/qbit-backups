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


import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.manifest.TableFingerprints;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Rebuilds the catalog from storage (spec 9.6): every manifest is
 ** self-describing, so after the database with the catalog is lost, the manifests
 ** under a bucket alias and prefix are listed, validated, and turned back into
 ** backups_run and backups_artifact rows (trigger IMPORTED).  The content
 ** addressed file store is re-registered in backups_file_object so dedup keeps
 ** working.  Importing is idempotent: a run already in the catalog is skipped.
 **
 ** Validation: the manifest must be readable and of a known version, its run key
 ** must match its folder, checksums.sha256 must exist and agree with the manifest
 ** for every part, and every part must exist with the recorded size.  The checksum
 ** of the manifest itself is computed and stored on the run, so a later restore
 ** detects a changed manifest.
 *******************************************************************************/
public class CatalogImporter
{
   private static final Pattern PART_NUMBER = Pattern.compile("part-(\\d+)");
   private static final Pattern SHA_KEY     = Pattern.compile("^[0-9a-f]{64}$");

   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    ** What was found for one manifest.
    *******************************************************************************/
   public record Inspection(String manifestKey, String status, List<String> problems, BackupManifest manifest, String runPrefix, String manifestSha256)
   {
      public static final String NEW     = "NEW";
      public static final String EXISTS  = "EXISTS";
      public static final String INVALID = "INVALID";
   }



   /*******************************************************************************
    ** The keys of every manifest under a prefix.
    *******************************************************************************/
   public List<String> findManifestKeys(BackupStorage storage, String prefix)
   {
      List<String> keys = new ArrayList<>();
      for(StoredObjectInfo info : storage.list(prefix == null ? "" : prefix))
      {
         if(info.key().endsWith("/manifest.json"))
         {
            keys.add(info.key());
         }
      }
      return (keys);
   }



   /*******************************************************************************
    ** Read and validate one manifest, and say whether the catalog already has it.
    *******************************************************************************/
   public Inspection inspect(BackupStorage storage, String manifestKey) throws QException
   {
      String runPrefix = manifestKey.substring(0, manifestKey.length() - "manifest.json".length());
      byte[] bytes;
      BackupManifest manifest;
      try
      {
         bytes = BackupSource.readAll(storage.openRead(manifestKey));
         manifest = BackupManifest.read(bytes);
      }
      catch(IOException | RuntimeException e)
      {
         return (invalid(manifestKey, runPrefix, "The manifest cannot be read: " + e.getMessage()));
      }

      String sha = HashingOutputStream.sha256Hex(bytes);
      List<String> problems = new ArrayList<>();
      if(manifest.manifestVersion() > BackupManifest.CURRENT_VERSION)
      {
         problems.add("The manifest is version " + manifest.manifestVersion() + ", newer than this qbit understands");
      }
      String[] segments = runPrefix.split("/");
      if(manifest.runKey() == null || segments.length == 0 || !manifest.runKey().equals(segments[segments.length - 1]))
      {
         problems.add("The manifest's run key does not match its folder");
      }

      if(!problems.isEmpty())
      {
         return (new Inspection(manifestKey, Inspection.INVALID, problems, manifest, runPrefix, sha));
      }

      if(!catalog.where(BackupsTables.RUN, "runKey", manifest.runKey()).isEmpty())
      {
         return (new Inspection(manifestKey, Inspection.EXISTS, List.of(), manifest, runPrefix, sha));
      }

      checkParts(storage, manifest, runPrefix, problems);
      return (new Inspection(manifestKey, problems.isEmpty() ? Inspection.NEW : Inspection.INVALID, problems, manifest, runPrefix, sha));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private Inspection invalid(String manifestKey, String runPrefix, String problem)
   {
      return (new Inspection(manifestKey, Inspection.INVALID, List.of(problem), null, runPrefix, null));
   }



   /*******************************************************************************
    ** Check checksums.sha256 against the manifest, and that each part exists.
    *******************************************************************************/
   private void checkParts(BackupStorage storage, BackupManifest manifest, String runPrefix, List<String> problems)
   {
      Map<String, String> checksums = readChecksums(storage, runPrefix);
      if(checksums == null)
      {
         problems.add("checksums.sha256 is missing or unreadable");
         return;
      }

      for(BackupManifest.TableEntry table : manifest.tables() == null ? List.<BackupManifest.TableEntry>of() : manifest.tables())
      {
         for(BackupManifest.Part part : table.parts())
         {
            String listed = checksums.get(part.key());
            if(listed == null || !listed.equals(part.sha256Stored()))
            {
               problems.add("The checksum of " + part.key() + " does not match the manifest");
            }
            else if(storage.head(runPrefix + part.key()).map(StoredObjectInfo::size).orElse(-1L) != part.bytesStored())
            {
               problems.add("The part " + part.key() + " is missing or has the wrong size");
            }
         }
      }
   }



   /*******************************************************************************
    ** Create the catalog rows of one valid manifest.  Returns the new run id.
    *******************************************************************************/
   public Integer importRun(String bucketAlias, BackupStorage storage, Inspection inspection) throws QException
   {
      BackupManifest manifest  = inspection.manifest();
      String         runPrefix = inspection.runPrefix();
      List<BackupManifest.TableEntry> tables = manifest.tables() == null ? List.of() : manifest.tables();

      Instant started = parse(manifest.startedAt());
      Instant ended   = parse(manifest.finishedAt());
      Integer planId  = null;
      if(manifest.plan() != null && manifest.plan().planKey() != null)
      {
         List<QRecord> plans = catalog.where(BackupsTables.PLAN, "planKey", manifest.plan().planKey());
         planId = plans.isEmpty() ? null : plans.get(0).getValueInteger("id");
      }

      Map<String, Serializable> row = new LinkedHashMap<>();
      row.put("backupPlanId", planId);
      row.put("runKey", manifest.runKey());
      row.put("trigger", "IMPORTED");
      row.put("requestedByUserId", manifest.requestedBy());
      row.put("status", manifest.warnings() == null || manifest.warnings().isEmpty() ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS");
      row.put("startDate", started);
      row.put("endDate", ended);
      row.put("durationMs", started != null && ended != null ? ended.toEpochMilli() - started.toEpochMilli() : null);
      row.put("tablesPlanned", tables.size());
      row.put("tablesDone", tables.size());
      row.put("rowsTotal", manifest.totals() == null ? null : manifest.totals().rows());
      row.put("bytesStored", manifest.totals() == null ? null : manifest.totals().bytesStored());
      row.put("consistencyAchieved", manifest.consistency() == null ? null : manifest.consistency().achieved());
      row.put("storageBucketAlias", bucketAlias);
      row.put("runPrefix", runPrefix);
      row.put("manifestKey", inspection.manifestKey());
      row.put("manifestSha256", inspection.manifestSha256());
      row.put("securityScope", manifest.securityScope());
      row.put("qqqVersion", manifest.instance() == null ? null : manifest.instance().qqqVersion());
      row.put("appVersion", manifest.instance() == null ? null : manifest.instance().appVersion());
      row.put("instanceName", manifest.instance() == null ? null : manifest.instance().name());
      row.put("schemaFingerprint", TableFingerprints.combine(tables.stream().map(BackupManifest.TableEntry::fingerprint).toList()));
      boolean encrypted = manifest.encryption() != null && manifest.encryption().mode() != null && !"NONE".equals(manifest.encryption().mode());
      row.put("encryptionKeyRef", manifest.encryption() == null ? null : manifest.encryption().keyRef());
      row.put("encryptionKeyFingerprint", manifest.encryption() == null ? null : manifest.encryption().keyFingerprint());
      row.put("verificationStatus", "UNVERIFIED");
      row.put("warningCount", manifest.warnings() == null ? 0 : manifest.warnings().size());
      if(manifest.files() != null)
      {
         row.put("filesReferenced", (int) manifest.files().objects());
         row.put("filesMissing", (int) manifest.files().missing());
         row.put("fileBytesReferenced", manifest.files().bytes());
      }
      Integer runId = catalog.insert(BackupsTables.RUN, row);

      Map<String, String> checksums = readChecksums(storage, runPrefix);
      if(checksums == null)
      {
         checksums = new HashMap<>();
      }
      for(BackupManifest.TableEntry table : tables)
      {
         for(int i = 0; i < table.parts().size(); i++)
         {
            BackupManifest.Part part = table.parts().get(i);
            Matcher matcher = PART_NUMBER.matcher(part.key());
            int number = matcher.find() ? Integer.parseInt(matcher.group(1)) : i + 1;
            boolean last = i == table.parts().size() - 1;
            catalog.insertArtifact(runId, "TABLE_DATA", table.name(), number, part.key(), part.format(), part.compression(), part.encrypted(), part.rows(),
               part.bytesRaw(), part.bytesStored(), part.sha256Stored(), part.sha256Raw(), last ? table.rowsHash() : null, null, null, table.fingerprint());
         }
      }
      if(manifest.files() != null && manifest.files().indexKeys() != null)
      {
         int number = 1;
         for(String key : manifest.files().indexKeys())
         {
            long size = storage.head(runPrefix + key).map(StoredObjectInfo::size).orElse(0L);
            catalog.insertArtifact(runId, "FILE_OBJECTS_INDEX", null, number++, key, "JSONL", "GZIP", encrypted, 0, 0, size, checksums.get(key), null, null, null, null, null);
         }
      }
      artifactOfKey(storage, runPrefix, "checksums.sha256", "CHECKSUMS", "TEXT", runId, null);
      artifactOfKey(storage, runPrefix, "manifest.json", "MANIFEST", "JSON", runId, inspection.manifestSha256());

      registerFileStore(bucketAlias, storage, runPrefix, runId, manifest, encrypted);
      return (runId);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void artifactOfKey(BackupStorage storage, String runPrefix, String key, String type, String format, Integer runId, String knownSha) throws QException
   {
      long size = storage.head(runPrefix + key).map(StoredObjectInfo::size).orElse(0L);
      String sha = knownSha;
      if(sha == null)
      {
         try
         {
            sha = HashingOutputStream.sha256Hex(BackupSource.readAll(storage.openRead(runPrefix + key)));
         }
         catch(IOException | RuntimeException e)
         {
            sha = null;
         }
      }
      catalog.insertArtifact(runId, type, null, 1, key, format, "NONE", false, 0, size, size, sha, sha, null, null, null, null);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private Map<String, String> readChecksums(BackupStorage storage, String runPrefix)
   {
      Map<String, String> checksums = new HashMap<>();
      try
      {
         for(String line : new String(BackupSource.readAll(storage.openRead(runPrefix + "checksums.sha256")), StandardCharsets.UTF_8).split("\n"))
         {
            String[] parts = line.split("  ", 2);
            if(parts.length == 2)
            {
               checksums.put(parts[1].trim(), parts[0].trim());
            }
         }
      }
      catch(IOException | RuntimeException e)
      {
         return (null);
      }
      return (checksums);
   }



   /*******************************************************************************
    ** Re-register the content-addressed files of the instance, so the next backup
    ** deduplicates against them again.
    *******************************************************************************/
   private void registerFileStore(String bucketAlias, BackupStorage storage, String runPrefix, Integer runId, BackupManifest manifest, boolean encrypted)
   {
      String[] segments = runPrefix.split("/");
      if(segments.length < 6)
      {
         return;
      }
      String instancePrefix = String.join("/", java.util.Arrays.copyOf(segments, segments.length - 5)) + "/";
      String fingerprint = manifest.encryption() == null ? null : manifest.encryption().keyFingerprint();
      var registry = catalog.fileStoreRegistry(bucketAlias, runId, fingerprint);
      for(StoredObjectInfo info : storage.list(instancePrefix + "_files/sha256/"))
      {
         String name = info.key().substring(info.key().lastIndexOf('/') + 1);
         int dot = name.indexOf('.');
         String sha = dot < 0 ? name : name.substring(0, dot);
         if(SHA_KEY.matcher(sha).matches() && !registry.isKnown(sha))
         {
            registry.register(sha, info.size(), null, info.key(), encrypted);
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Instant parse(String text)
   {
      try
      {
         return (text == null ? null : Instant.parse(text));
      }
      catch(RuntimeException e)
      {
         return (null);
      }
   }
}
