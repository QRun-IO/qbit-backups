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


import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.codec.ArtifactTransform;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.codec.JsonlRowCodec;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** TEST_RESTORE verification (design spec section 10.1): restore every table of the
 ** run into a scratch target (MEMORY by default), read it back in primary key order,
 ** and compare each table's row count and rowsHash with the manifest; restore the
 ** run's files into the scratch prefix qqq-verify/(runKey)/ in the backup bucket,
 ** re-hash each one, and check that every file reference of the index resolves to a
 ** restored file.  Then drop the scratch tables and delete the scratch files, even
 ** when something fails.
 **
 ** The scratch target is BackupsQBitConfig.getScratchTarget(); the MEMORY target
 ** proves the files and the codecs, not the database.  RDBMS_SCHEMA is a stub until
 ** the restore agent's writer ships (see RdbmsSchemaScratchTarget).  Sequence resync
 ** is part of the real restore (spec 5.4) and not exercised by the MEMORY target.
 *******************************************************************************/
public class TestRestoreVerifier
{
   private static final int PAGE_SIZE = 1000;

   public static final String SCRATCH_FILE_PREFIX = "qqq-verify/";



   /*******************************************************************************
    ** Run the test restore, recording what it finds.  Returns the scratch target
    ** description for backups_verification.scratchTarget.
    *******************************************************************************/
   String verify(RunBundle bundle, VerifyFindings findings) throws QException
   {
      ScratchTarget target = BackupsRuntime.getConfig() != null && BackupsRuntime.getConfig().getScratchTarget() != null
         ? BackupsRuntime.getConfig().getScratchTarget() : new MemoryScratchTarget();
      String runKey  = bundle.manifest().runKey();
      String scratch = target.describe(runKey);

      List<String> scratchFileKeys = new ArrayList<>();
      try
      {
         findings.tablesChecked = bundle.manifest().tables().size();
         restoreTables(bundle, target, runKey, findings);
         restoreFiles(bundle, runKey, scratchFileKeys, findings);
      }
      finally
      {
         try
         {
            target.drop(runKey);
         }
         finally
         {
            for(String key : scratchFileKeys)
            {
               bundle.storage().deletePermanent(key);
            }
         }
      }
      return (scratch);
   }



   /*******************************************************************************
    ** Restore each table into the scratch target and compare it with the manifest.
    *******************************************************************************/
   private void restoreTables(RunBundle bundle, ScratchTarget target, String runKey, VerifyFindings findings) throws QException
   {
      BackupManifest manifest = bundle.manifest();
      List<String>   order    = new ArrayList<>(manifest.restoreOrder() == null ? List.of() : manifest.restoreOrder());
      for(BackupManifest.TableEntry entry : manifest.tables())
      {
         if(!order.contains(entry.name()))
         {
            order.add(entry.name());
         }
      }

      for(String name : order)
      {
         BackupManifest.TableEntry entry = manifest.table(name);
         if(entry == null)
         {
            continue;
         }
         QTableMetaData table = QContext.getQInstance().getTable(name);
         if(table == null)
         {
            findings.mismatch("Table " + name + " no longer exists in this instance, so it cannot be test-restored");
            continue;
         }
         boolean hasJsonl = entry.parts() != null && entry.parts().stream().anyMatch(p -> !"CSV".equals(p.format()));
         if(!hasJsonl)
         {
            findings.warn("Table " + name + " has only CSV parts and was not test-restored");
            continue;
         }

         List<String> excluded = entry.excludedFields() == null ? List.of() : entry.excludedFields().stream().map(BackupManifest.ExcludedField::name).toList();
         JsonlRowCodec codec = new JsonlRowCodec(table, excluded);
         ScratchTable  scratchTable = target.create(table, runKey);

         int before = findings.mismatches;
         restoreRows(bundle, entry, codec, scratchTable, findings);

         MessageDigest digest = HashingOutputStream.newSha256();
         long[]        count  = { 0 };
         scratchTable.readInPrimaryKeyOrder(PAGE_SIZE, record ->
         {
            count[0]++;
            digest.update((codec.encode(record) + "\n").getBytes(StandardCharsets.UTF_8));
         });
         String actualHash = "sha256:" + HashingOutputStream.hex(digest.digest());

         if(count[0] != entry.rowCount())
         {
            findings.mismatch("Test restore of table " + name + " has " + count[0] + " rows, the manifest says " + entry.rowCount());
         }
         if(entry.rowsHash() != null && !entry.rowsHash().equals(actualHash))
         {
            findings.mismatch("Test restore of table " + name + " does not reproduce the backed up rows (row content hash differs)");
         }
         findings.rowsChecked += count[0];

         Map<String, Object> detail = new LinkedHashMap<>();
         detail.put("table", name);
         detail.put("expectedRows", entry.rowCount());
         detail.put("actualRows", count[0]);
         detail.put("expectedRowsHash", entry.rowsHash());
         detail.put("actualRowsHash", actualHash);
         detail.put("ok", findings.mismatches == before);
         findings.tables.add(detail);
      }
   }



   /*******************************************************************************
    ** Decode the JSONL parts of one table and insert them into the scratch table.
    *******************************************************************************/
   private void restoreRows(RunBundle bundle, BackupManifest.TableEntry entry, JsonlRowCodec codec, ScratchTable scratchTable, VerifyFindings findings) throws QException
   {
      for(BackupManifest.Part part : entry.parts())
      {
         if("CSV".equals(part.format()))
         {
            continue;
         }
         List<QRecord> batch = new ArrayList<>();
         try
         {
            PartScanner.scan(bundle, part, line ->
            {
               batch.add(codec.decode(line));
               if(batch.size() >= PAGE_SIZE)
               {
                  scratchTable.insert(new ArrayList<>(batch));
                  batch.clear();
               }
            });
            if(!batch.isEmpty())
            {
               scratchTable.insert(batch);
            }
         }
         catch(IOException | RuntimeException e)
         {
            findings.mismatch("The part " + part.key() + " could not be restored (" + e.getClass().getSimpleName() + "); it is corrupted, or the key is wrong");
         }
      }
   }



   /*******************************************************************************
    ** Restore the run's files into the scratch prefix, re-hash each, and check that
    ** every index reference resolves to a restored file.  verifyFileSamplePercent
    ** (on the plan, default 100) limits how many files are restored.
    *******************************************************************************/
   private void restoreFiles(RunBundle bundle, String runKey, List<String> scratchFileKeys, VerifyFindings findings)
   {
      int percent = 100;
      if(bundle.plan() != null && bundle.plan().getValueInteger("verifyFileSamplePercent") != null)
      {
         percent = Math.max(0, Math.min(100, bundle.plan().getValueInteger("verifyFileSamplePercent")));
      }

      BackupStorage storage  = bundle.storage();
      String        prefix   = SCRATCH_FILE_PREFIX + runKey + "/";
      Set<String>   restored = new LinkedHashSet<>();
      int           sampled  = 0;
      for(RunBundle.FileIndexEntry entry : bundle.distinctStoredFiles(findings))
      {
         if(Math.floorMod(entry.sha256().hashCode(), 100) >= percent)
         {
            continue;
         }
         sampled++;
         String scratchKey = prefix + entry.sha256();
         scratchFileKeys.add(scratchKey);
         try
         {
            ArtifactTransform transform = bundle.isEncrypted() ? bundle.transform() : ArtifactTransform.IDENTITY;
            String            written;
            try(InputStream raw = storage.openRead(entry.casKey());
                InputStream plain = transform.unwrap(raw);
                HashingOutputStream out = new HashingOutputStream(storage.openWrite(scratchKey, WriteOptions.create())))
            {
               plain.transferTo(out);
               out.close();
               written = out.getSha256();
            }
            String reread = BackupVerifier.hashObject(storage, scratchKey, ArtifactTransform.IDENTITY);
            if(written.equals(entry.sha256()) && reread.equals(entry.sha256()))
            {
               restored.add(entry.sha256());
            }
            else
            {
               findings.mismatch("Test restore of file sha256 " + entry.sha256() + " does not reproduce its content");
            }
         }
         catch(IOException | RuntimeException e)
         {
            findings.mismatch("Test restore of file sha256 " + entry.sha256() + " failed (" + e.getClass().getSimpleName() + ")");
         }
      }

      int unresolved = 0;
      for(RunBundle.FileIndexEntry entry : bundle.allFileEntries(findings))
      {
         if(entry.isStored() && Math.floorMod(entry.sha256().hashCode(), 100) < percent && !restored.contains(entry.sha256()))
         {
            unresolved++;
         }
      }
      if(unresolved > 0)
      {
         findings.mismatch(unresolved + " file references in the index do not resolve to a restored file");
      }
      findings.filesChecked += sampled;
      findings.extra.put("filesRestored", restored.size());
   }
}
