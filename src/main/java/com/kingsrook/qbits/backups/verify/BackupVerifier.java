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
import java.io.OutputStream;
import java.io.Serializable;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.alerts.BackupAlerts;
import com.kingsrook.qbits.backups.codec.ArtifactTransform;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.codec.JsonlRowCodec;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.manifest.TableFingerprints;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.BackupStorageException;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope;
import com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Verifies backup runs (design spec section 10.1).  Three levels, each a row in
 ** backups_verification:
 ** <ul>
 **    <li>STORAGE: head every object of the run, including every file-store object
 **    the run's file index references; sizes (and a provider checksum, where the
 **    provider stores one) must match the manifest.</li>
 **    <li>CONTENT: read every byte.  Each part is decrypted (the decrypt hook,
 **    ArtifactTransform.unwrap), gunzipped and parsed row by row with the codec;
 **    row counts, sha256Stored, sha256Raw and the table rowsHash are recomputed and
 **    compared with the manifest.  Every referenced file-store object is decrypted
 **    and re-hashed against its SHA-256 key, except those verified within
 **    fileReverifyDays (backups_file_object.lastVerifiedDate).</li>
 **    <li>TEST_RESTORE: see {@link TestRestoreVerifier}.</li>
 ** </ul>
 ** The result updates backups_run.verificationStatus and backups_plan.lastVerified*,
 ** and a failure raises backups.verify.failed.  A mismatch is a FAILED verification;
 ** a verification that could not run (storage unreachable, no key) is an ERROR.
 *******************************************************************************/
public class BackupVerifier
{
   private static final QLogger LOG = QLogger.getLogger(BackupVerifier.class);

   private static final ObjectMapper MAPPER = new ObjectMapper();

   public static final String STORAGE      = "STORAGE";
   public static final String CONTENT      = "CONTENT";
   public static final String TEST_RESTORE = "TEST_RESTORE";

   /** The levels in order; each includes the ones before it when run through. */
   public static final List<String> LEVELS = List.of(STORAGE, CONTENT, TEST_RESTORE);

   private static final List<String> STATUS_RANK = List.of("UNVERIFIED", "STORAGE_OK", "CONTENT_OK", "TEST_RESTORE_OK");

   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    ** Verify a run at each level up to and including the given one, stopping at the
    ** first level that does not pass.  Returns one outcome per level that ran.
    *******************************************************************************/
   public List<VerificationOutcome> verifyThrough(Integer runId, String maxLevel, String trigger, String requestedBy) throws QException
   {
      List<VerificationOutcome> outcomes = new ArrayList<>();
      for(String level : LEVELS.subList(0, LEVELS.indexOf(maxLevel) + 1))
      {
         VerificationOutcome outcome = verify(runId, level, trigger, requestedBy);
         outcomes.add(outcome);
         if(!outcome.passed())
         {
            break;
         }
      }
      return (outcomes);
   }



   /*******************************************************************************
    ** Verify a run at one level.  Never throws for a backup problem: the outcome
    ** carries the status and the first messages.
    *******************************************************************************/
   public VerificationOutcome verify(Integer runId, String level, String trigger, String requestedBy) throws QException
   {
      if(!LEVELS.contains(level))
      {
         throw (new QException("Unknown verification level: " + level));
      }
      QRecord run = catalog.get(BackupsTables.RUN, runId).orElseThrow(() -> new QException("No backup run with id " + runId));
      QRecord plan = run.getValueInteger("backupPlanId") == null ? null : catalog.get(BackupsTables.PLAN, run.getValueInteger("backupPlanId")).orElse(null);

      Instant started = BackupsRuntime.getClock().instant();
      Map<String, Serializable> row = new LinkedHashMap<>();
      row.put("backupRunId", runId);
      row.put("level", level);
      row.put("trigger", trigger);
      row.put("requestedByUserId", requestedBy);
      row.put("startDate", started);
      row.put("status", "RUNNING");
      Integer verificationId = catalog.insert(BackupsTables.VERIFICATION, row);

      VerifyFindings findings = new VerifyFindings();
      String         status;
      String         scratch = null;
      try
      {
         RunBundle bundle = RunBundle.load(run, plan, findings);
         if(bundle != null)
         {
            switch(level)
            {
               case STORAGE -> storageLevel(bundle, findings);
               case CONTENT -> contentLevel(bundle, findings);
               default -> scratch = new TestRestoreVerifier().verify(bundle, findings);
            }
         }
         status = findings.status();
      }
      catch(JobInterruptedException e)
      {
         throw (e);
      }
      catch(VerifyException | BackupStorageException | QException e)
      {
         status = "ERROR";
         findings.mismatch("The verification could not be carried out: " + e.getMessage());
      }
      catch(RuntimeException e)
      {
         LOG.warn("Verification crashed", e, logPair("runId", runId), logPair("level", level));
         status = "ERROR";
         findings.mismatch("The verification crashed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }

      Instant ended = BackupsRuntime.getClock().instant();
      VerificationOutcome outcome = new VerificationOutcome(verificationId, runId, level, status, findings.tablesChecked, findings.rowsChecked, findings.mismatches,
         findings.objectsChecked, findings.filesChecked, findings.filesSkipped, findings.messages(), scratch);

      Map<String, Serializable> done = new LinkedHashMap<>();
      done.put("endDate", ended);
      done.put("status", status);
      done.put("tablesChecked", findings.tablesChecked);
      done.put("rowsChecked", findings.rowsChecked);
      done.put("mismatches", findings.mismatches);
      done.put("scratchTarget", scratch);
      done.put("detailJson", detailJson(findings));
      catalog.update(BackupsTables.VERIFICATION, verificationId, done);

      applyOutcome(run, plan, outcome, ended);
      return (outcome);
   }



   /*******************************************************************************
    ** STORAGE level: head every object, including every file-store object.
    *******************************************************************************/
   void storageLevel(RunBundle bundle, VerifyFindings findings) throws QException
   {
      BackupManifest manifest = bundle.manifest();
      BackupStorage  storage  = bundle.storage();
      findings.tablesChecked = manifest.tables().size();
      findings.objectsChecked++;

      headOrMismatch(storage, bundle.runPrefix() + "checksums.sha256", "checksums.sha256", findings);

      for(BackupManifest.TableEntry table : manifest.tables())
      {
         JobChunkScope.checkBetweenPages();
         for(BackupManifest.Part part : table.parts() == null ? List.<BackupManifest.Part>of() : table.parts())
         {
            findings.objectsChecked++;
            Optional<StoredObjectInfo> head = storage.head(bundle.runPrefix() + part.key());
            if(head.isEmpty())
            {
               findings.mismatch("Missing object: " + part.key() + " (table " + table.name() + ")");
               continue;
            }
            if(head.get().size() != part.bytesStored())
            {
               findings.mismatch("Size mismatch for " + part.key() + ": the manifest says " + part.bytesStored() + " bytes, the bucket has " + head.get().size());
            }
            String providerSha = head.get().metadata() == null ? null : head.get().metadata().get("sha256");
            if(providerSha != null && !providerSha.equalsIgnoreCase(part.sha256Stored()))
            {
               findings.mismatch("Checksum mismatch for " + part.key() + ": the provider's stored SHA-256 differs from the manifest");
            }
         }
      }

      if(manifest.files() != null && manifest.files().indexKeys() != null)
      {
         for(String indexKey : manifest.files().indexKeys())
         {
            headOrMismatch(storage, bundle.runPrefix() + indexKey, indexKey, findings);
         }
      }

      int missingFiles = 0;
      for(RunBundle.FileIndexEntry entry : bundle.distinctStoredFiles(findings))
      {
         JobChunkScope.checkBetweenPages();
         findings.objectsChecked++;
         findings.filesChecked++;
         Optional<StoredObjectInfo> head = storage.head(entry.casKey());
         if(head.isEmpty())
         {
            missingFiles++;
            findings.mismatch("File store object missing: sha256 " + entry.sha256() + " (referenced by " + entry.table() + ")");
         }
         else if(!bundle.isEncrypted() && entry.size() >= 0 && head.get().size() != entry.size())
         {
            findings.mismatch("File store object has the wrong size: sha256 " + entry.sha256() + " should be " + entry.size() + " bytes, the bucket has " + head.get().size());
         }
      }
      findings.extra.put("filesMissingFromStore", missingFiles);
   }



   /*******************************************************************************
    ** CONTENT level: decrypt, gunzip, parse, recount and re-hash everything.
    *******************************************************************************/
   void contentLevel(RunBundle bundle, VerifyFindings findings) throws QException
   {
      BackupManifest manifest  = bundle.manifest();
      QInstance      qInstance = QContext.getQInstance();
      findings.tablesChecked = manifest.tables().size();

      checkChecksumsFile(bundle, findings);

      for(BackupManifest.TableEntry entry : manifest.tables())
      {
         JobChunkScope.checkBetweenPages();
         QTableMetaData table = qInstance.getTable(entry.name());
         JsonlRowCodec  codec = null;
         boolean        roundTrip = false;
         if(table == null)
         {
            findings.warn("Table " + entry.name() + " no longer exists in this instance; its rows were checked as JSON, not with the codec");
         }
         else
         {
            List<String> excluded = entry.excludedFields() == null ? List.of() : entry.excludedFields().stream().map(BackupManifest.ExcludedField::name).toList();
            codec = new JsonlRowCodec(table, excluded);
            String backendType = qInstance.getBackend(table.getBackendName()) == null ? null : qInstance.getBackend(table.getBackendName()).getBackendType();
            roundTrip = TableFingerprints.matches(entry, table, backendType);
            if(!roundTrip)
            {
               findings.warn("Table " + entry.name() + " has changed since this backup; rows were parsed but not checked for an exact round trip");
            }
         }
         verifyTable(bundle, entry, codec, roundTrip, findings);
      }

      verifyFiles(bundle, findings);
   }



   /*******************************************************************************
    ** All parts of one table.
    *******************************************************************************/
   private void verifyTable(RunBundle bundle, BackupManifest.TableEntry entry, JsonlRowCodec codec, boolean roundTrip, VerifyFindings findings) throws QException
   {
      MessageDigest rowsDigest = HashingOutputStream.newSha256();
      long          rows       = 0;
      boolean       hasJsonl   = false;
      int           partCount  = 0;
      int           before     = findings.mismatches;

      for(BackupManifest.Part part : entry.parts() == null ? List.<BackupManifest.Part>of() : entry.parts())
      {
         partCount++;
         findings.objectsChecked++;
         boolean jsonl = !"CSV".equals(part.format());
         hasJsonl |= jsonl;
         long[] parsedRows = { 0 };
         try
         {
            PartScanner.Scan scan = PartScanner.scan(bundle, part, jsonl ? line ->
            {
               parsedRows[0]++;
               rowsDigest.update((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
               checkRow(entry.name(), part.key(), parsedRows[0], line, codec, roundTrip, findings);
            } : null);

            if(!scan.sha256Stored().equals(part.sha256Stored()))
            {
               findings.mismatch("Stored checksum mismatch for " + part.key() + ": the bytes in the bucket are not the bytes that were written (corrupted or replaced)");
            }
            if(!scan.sha256Raw().equals(part.sha256Raw()))
            {
               findings.mismatch("Content checksum mismatch for " + part.key() + ": the decoded content differs from what was written");
            }
            if(jsonl && scan.lines() != part.rows())
            {
               findings.mismatch("Row count mismatch for " + part.key() + ": the manifest says " + part.rows() + " rows, the part has " + scan.lines());
            }
            if(jsonl)
            {
               rows += scan.lines();
            }
         }
         catch(IOException | RuntimeException e)
         {
            findings.mismatch("The part " + part.key() + " could not be read, decrypted or decompressed (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())
               + "); it is corrupted, or the key is wrong");
         }
         catch(QException e)
         {
            JobInterruptedException interrupted = JobInterruptedException.find(e);
            if(interrupted != null)
            {
               throw (interrupted);
            }
            findings.mismatch("The part " + part.key() + " could not be read: " + e.getMessage());
         }
      }

      String actualHash = hasJsonl ? "sha256:" + HashingOutputStream.hex(rowsDigest.digest()) : null;
      if(hasJsonl)
      {
         findings.rowsChecked += rows;
         if(rows != entry.rowCount())
         {
            findings.mismatch("Row count mismatch for table " + entry.name() + ": the manifest says " + entry.rowCount() + ", the parts hold " + rows);
         }
         if(entry.rowsHash() != null && !entry.rowsHash().equals(actualHash))
         {
            findings.mismatch("Row content hash mismatch for table " + entry.name() + ": the rows are not the rows that were backed up");
         }
      }
      else
      {
         findings.warn("Table " + entry.name() + " has only CSV parts; their bytes were hashed but rows were not recounted");
      }

      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("table", entry.name());
      detail.put("parts", partCount);
      detail.put("expectedRows", entry.rowCount());
      detail.put("actualRows", hasJsonl ? rows : null);
      detail.put("expectedRowsHash", entry.rowsHash());
      detail.put("actualRowsHash", actualHash);
      detail.put("ok", findings.mismatches == before);
      findings.tables.add(detail);
   }



   /*******************************************************************************
    ** Parse one row with the codec (and check the exact round trip when the table
    ** is unchanged).  Without a table, check only that it is a JSON object.
    *******************************************************************************/
   private void checkRow(String table, String partKey, long rowNumber, String line, JsonlRowCodec codec, boolean roundTrip, VerifyFindings findings) throws QException
   {
      try
      {
         if(codec == null)
         {
            if(!MAPPER.readTree(line).isObject())
            {
               throw (new QException("not a JSON object"));
            }
            return;
         }
         QRecord record = codec.decode(line);
         if(roundTrip && !codec.encode(record).equals(line))
         {
            findings.mismatch("Row " + rowNumber + " of " + partKey + " does not survive a decode and encode round trip (table " + table + ")");
         }
      }
      catch(IOException | QException e)
      {
         findings.mismatch("Row " + rowNumber + " of " + partKey + " cannot be parsed (table " + table + ")");
      }
   }



   /*******************************************************************************
    ** The checksums.sha256 file must list every part with its stored SHA-256.
    *******************************************************************************/
   private void checkChecksumsFile(RunBundle bundle, VerifyFindings findings)
   {
      String key = bundle.runPrefix() + "checksums.sha256";
      findings.objectsChecked++;
      try(InputStream in = bundle.storage().openRead(key))
      {
         String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
         for(BackupManifest.TableEntry table : bundle.manifest().tables())
         {
            for(BackupManifest.Part part : table.parts() == null ? List.<BackupManifest.Part>of() : table.parts())
            {
               if(!text.contains(part.sha256Stored() + "  " + part.key()))
               {
                  findings.mismatch("checksums.sha256 does not list " + part.key() + " with the manifest's checksum");
               }
            }
         }
      }
      catch(BackupStorageException e)
      {
         findings.mismatch("checksums.sha256 is missing from the bucket");
      }
      catch(IOException e)
      {
         findings.mismatch("checksums.sha256 cannot be read: " + e.getClass().getSimpleName());
      }
   }



   /*******************************************************************************
    ** Re-hash every referenced file-store object against its SHA-256 key, skipping
    ** those verified within fileReverifyDays.
    *******************************************************************************/
   private void verifyFiles(RunBundle bundle, VerifyFindings findings) throws QException
   {
      BackupsQBitConfig config   = BackupsRuntime.getConfig();
      int               days     = config == null || config.getFileReverifyDays() == null ? 30 : config.getFileReverifyDays();
      Instant           now      = BackupsRuntime.getClock().instant();
      Instant           cutoff   = now.minus(Duration.ofDays(days));
      int               failed   = 0;

      for(RunBundle.FileIndexEntry entry : bundle.distinctStoredFiles(findings))
      {
         JobChunkScope.checkBetweenPages();
         QRecord fileObject = catalog.where(BackupsTables.FILE_OBJECT, "sha256", entry.sha256()).stream().filter(r -> r.getValue("deletedDate") == null).findFirst().orElse(null);
         if(fileObject != null && "PASSED".equals(fileObject.getValueString("lastVerifyStatus"))
            && fileObject.getValueInstant("lastVerifiedDate") != null && fileObject.getValueInstant("lastVerifiedDate").isAfter(cutoff))
         {
            findings.filesSkipped++;
            continue;
         }

         findings.filesChecked++;
         findings.objectsChecked++;
         boolean encrypted = fileObject != null ? Boolean.TRUE.equals(fileObject.getValueBoolean("encrypted")) : bundle.isEncrypted();
         boolean ok        = false;
         try
         {
            String actual = hashStoredFile(bundle, entry.casKey(), encrypted);
            ok = actual.equals(entry.sha256());
            if(!ok)
            {
               findings.mismatch("File store object sha256 " + entry.sha256() + " has the wrong content: re-hashing it gives " + actual);
            }
         }
         catch(BackupStorageException e)
         {
            findings.mismatch("File store object missing: sha256 " + entry.sha256() + " (referenced by " + entry.table() + ")");
         }
         catch(IOException | RuntimeException e)
         {
            findings.mismatch("File store object sha256 " + entry.sha256() + " could not be read or decrypted (" + e.getClass().getSimpleName() + ")");
         }

         if(!ok)
         {
            failed++;
         }
         if(fileObject != null)
         {
            Map<String, Serializable> update = new LinkedHashMap<>();
            update.put("lastVerifiedDate", now);
            update.put("lastVerifyStatus", ok ? "PASSED" : "FAILED");
            catalog.update(BackupsTables.FILE_OBJECT, fileObject.getValueInteger("id"), update);
         }
      }
      findings.extra.put("filesFailed", failed);
   }



   /*******************************************************************************
    ** SHA-256 of a stored file's plaintext (decrypted when it was encrypted).
    *******************************************************************************/
   static String hashStoredFile(RunBundle bundle, String key, boolean encrypted) throws IOException
   {
      return (hashObject(bundle.storage(), key, encrypted ? bundle.transform() : ArtifactTransform.IDENTITY));
   }



   /*******************************************************************************
    ** SHA-256 of an object read through a transform.
    *******************************************************************************/
   static String hashObject(BackupStorage storage, String key, ArtifactTransform transform) throws IOException
   {
      MessageDigest digest = HashingOutputStream.newSha256();
      try(InputStream raw = storage.openRead(key);
          DigestInputStream plain = new DigestInputStream(transform.unwrap(raw), digest))
      {
         plain.transferTo(OutputStream.nullOutputStream());
      }
      return (HashingOutputStream.hex(digest.digest()));
   }



   /*******************************************************************************
    ** Head an object, recording a mismatch when it is missing.
    *******************************************************************************/
   private void headOrMismatch(BackupStorage storage, String key, String label, VerifyFindings findings)
   {
      findings.objectsChecked++;
      if(storage.head(key).isEmpty())
      {
         findings.mismatch("Missing object: " + label);
      }
   }



   /*******************************************************************************
    ** The per-table detail and the messages, as JSON text.
    *******************************************************************************/
   private String detailJson(VerifyFindings findings)
   {
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("tables", findings.tables);
      detail.put("mismatches", findings.mismatchMessages);
      detail.put("warnings", findings.warnings);
      detail.put("objectsChecked", findings.objectsChecked);
      detail.put("filesChecked", findings.filesChecked);
      detail.put("filesSkipped", findings.filesSkipped);
      detail.putAll(findings.extra);
      try
      {
         return (MAPPER.writeValueAsString(detail));
      }
      catch(IOException e)
      {
         return ("{}");
      }
   }



   /*******************************************************************************
    ** Update the run, the plan and the alerts from an outcome.
    *******************************************************************************/
   private void applyOutcome(QRecord run, QRecord plan, VerificationOutcome outcome, Instant when) throws QException
   {
      Integer runId    = run.getValueInteger("id");
      String  current  = run.getValueString("verificationStatus");
      String  newState = null;
      if(outcome.passed())
      {
         String reached = switch(outcome.level())
         {
            case STORAGE -> "STORAGE_OK";
            case CONTENT -> "CONTENT_OK";
            default -> "TEST_RESTORE_OK";
         };
         boolean currentFailed = "FAILED".equals(current);
         // a cheap pass never clears a failure found by a deeper level
         if(!currentFailed || !STORAGE.equals(outcome.level()))
         {
            newState = !currentFailed && STATUS_RANK.indexOf(current) > STATUS_RANK.indexOf(reached) ? current : reached;
         }
      }
      else
      {
         newState = "FAILED";
      }
      if(newState != null && !newState.equals(current))
      {
         catalog.update(BackupsTables.RUN, runId, Map.of("verificationStatus", newState));
      }

      if(plan == null)
      {
         return;
      }
      String planKey = plan.getValueString("planKey");
      int    rank    = LEVELS.indexOf(outcome.level());
      if(outcome.passed())
      {
         for(int i = 0; i <= rank; i++)
         {
            BackupAlerts.resolveKey(BackupAlerts.VERIFY_FAILED, BackupAlerts.verifyKey(planKey, LEVELS.get(i)));
         }
         if(rank >= LEVELS.indexOf(CONTENT))
         {
            Map<String, Serializable> planUpdate = new LinkedHashMap<>();
            planUpdate.put("lastVerifiedRunId", runId);
            planUpdate.put("lastVerifiedDate", when);
            catalog.update(BackupsTables.PLAN, plan.getValueInteger("id"), planUpdate);
            BackupAlerts.resolve(BackupAlerts.VERIFY_OVERDUE, planKey);
         }
      }
      else
      {
         Map<String, String> detail = new LinkedHashMap<>();
         detail.put("plan", planKey);
         detail.put("run", run.getValueString("runKey"));
         detail.put("level", outcome.level());
         detail.put("mismatches", String.valueOf(outcome.mismatches()));
         BackupAlerts.raiseWithKey(BackupAlerts.VERIFY_FAILED, BackupAlerts.verifyKey(planKey, outcome.level()), BackupAlertSink.Severity.ERROR,
            "Backup verification failed (" + outcome.level() + ")", detail);
      }
   }
}
