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

package com.kingsrook.qbits.backups.run;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.codec.TableArtifactWriter;
import com.kingsrook.qbits.backups.events.BackupEvents;
import com.kingsrook.qbits.backups.files.FileCopier;
import com.kingsrook.qbits.backups.files.FileReference;
import com.kingsrook.qbits.backups.files.FileReferenceProvider;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.manifest.TableFingerprints;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope;
import com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException;
import com.kingsrook.qqq.backend.core.jobs.progress.JobPhase;
import com.kingsrook.qqq.backend.core.jobs.progress.JobProgress;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Executes one backup run (spec 8 and 9.2).
 **
 ** The order is the contract: the run row is created RUNNING; every table is
 ** streamed in primary key order into numbered parts (with their SHA-256 and the
 ** rowsHash); the files the rows reference are copied into the deduplicated
 ** content-addressed store; checksums.sha256 is written; and the manifest is
 ** written LAST, then checked with a head.  A run with no manifest did not
 ** complete.  The run row is updated every page (heartbeat), and a failure marks
 ** the run FAILED with a scrubbed message (never row values).
 *******************************************************************************/
public class BackupEngine
{
   private static final QLogger LOG = QLogger.getLogger(BackupEngine.class);

   private static final ObjectMapper MAPPER = new ObjectMapper();

   private static final DateTimeFormatter RUN_KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
   private static final DateTimeFormatter DATE_PATH      = DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

   private static final long MAX_ROWS_PER_PART = 1_000_000L;

   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    ** Run a backup.  Never throws for a backup failure: the result carries the
    ** status and message (and the run row is FAILED). Cooperative job interrupts
    ** propagate as QException so the durable worker handles cancellation/lease loss.
    *******************************************************************************/
   public BackupRunResult execute(BackupRunRequest request) throws QException
   {
      JobProgress progress = request.getProgress();
      if(!"prepare".equals(progress.getSnapshot().getCurrentPhase()))
      {
         prepare(progress);
      }
      BackupRunResult result  = new BackupRunResult();
      Instant         started = request.getClock().instant();
      String          runKey  = request.getResume() != null ? request.getResume().runKey() : request.getRunKey() != null ? request.getRunKey() : RUN_KEY_FORMAT.format(started) + "-" + UUID.randomUUID().toString().substring(0, 4);
      String          planPrefix = request.getSettings().string("prefix", "qqq-backups");
      String          instancePrefix = trimSlashes(planPrefix) + "/" + request.getInstanceName() + "/";
      String          runPrefix  = request.getResume() != null ? request.getResume().runPrefix() : instancePrefix + request.getSettings().planKey() + "/" + DATE_PATH.format(started) + "/" + runKey + "/";
      result.setRunKey(runKey);
      result.setRunPrefix(runPrefix);

      try
      {
         Map<String, Serializable> runValues = new LinkedHashMap<>();
         runValues.put("backupPlanId", request.getSettings().getPlanId());
         runValues.put("runKey", runKey);
         runValues.put("trigger", request.getTrigger());
         runValues.put("requestedByUserId", request.getRequestedBy());
         runValues.put("status", "RUNNING");
         runValues.put("startDate", started);
         runValues.put("heartbeatDate", started);
         runValues.put("tablesPlanned", request.getScope().tables().size());
         runValues.put("storageBucketAlias", request.getBucketAlias());
         runValues.put("runPrefix", runPrefix);
         runValues.put("instanceName", request.getInstanceName());
         runValues.put("appVersion", request.getAppVersion());
         runValues.put("securityScope", request.getSecurityScope());
         runValues.put("encryptionKeyRef", request.getEncryptionKeyRef());
         runValues.put("encryptionKeyFingerprint", request.getEncryptionKeyFingerprint());
         runValues.put("verificationStatus", "UNVERIFIED");
         if(request.getResume() != null)
         {
            ///////////////////////////////////////////////////////////////////////////////
            // a resume carries on the original attempt: keep its start, clear its error //
            ///////////////////////////////////////////////////////////////////////////////
            runValues.remove("startDate");
            runValues.put("endDate", null);
            runValues.put("errorMessage", null);
         }
         if(request.getExistingRunId() != null)
         {
            catalog.update(BackupsTables.RUN, request.getExistingRunId(), runValues);
            result.setRunId(request.getExistingRunId());
         }
         else
         {
            result.setRunId(catalog.insert(BackupsTables.RUN, runValues));
         }
      }
      catch(Exception e)
      {
         result.setStatus("FAILED");
         result.setErrorMessage("Could not create the run record (" + e.getClass().getSimpleName() + ")");
         failProgress(progress, "Could not create the run record (" + e.getClass().getSimpleName() + ")");
         return (result);
      }

      try
      {
         checkJob();
         if(request.getResume() != null)
         {
            long complete = request.getResume().tables().values().stream().filter(ResumeState.TableState::complete).count();
            progress.info("Resuming run " + result.getRunId() + ": " + complete + " tables already done");
         }
         doRun(request, result, runPrefix, instancePrefix, started);
         completePhase(progress, JobPhase.Status.DONE);
      }
      catch(CancelledException e)
      {
         finish(request, result, "CANCELLED", "Cancelled", started);
         progress.info("Backup cancelled");
      }
      catch(TimedOutException e)
      {
         finish(request, result, "TIMED_OUT", e.getMessage(), started);
         failProgress(progress, e.getMessage());
      }
      catch(Exception e)
      {
         JobInterruptedException interrupted = JobInterruptedException.find(e);
         if(interrupted != null)
         {
            if(interrupted.getReason() != JobInterruptedException.Reason.LEASE_LOST)
            {
               finish(request, result, interrupted.getReason() == JobInterruptedException.Reason.CANCEL ? "CANCELLED" : "ABANDONED", "Job interrupted", started);
            }
            throw (interrupted);
         }
         LOG.warn("Backup run failed", e, logPair("runKey", runKey));
         // Provider and reader messages can contain credentials or row values.
         failProgress(progress, scrub(e));
         finish(request, result, "FAILED", scrub(e), started);
      }
      finally
      {
         progress.flush();
      }
      return (result);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void doRun(BackupRunRequest request, BackupRunResult result, String runPrefix, String instancePrefix, Instant started) throws Exception
   {
      JobProgress      progress = request.getProgress();
      QInstance        qInstance = QContext.getQInstance();
      BackupStorage    storage   = request.getStorage();
      var              settings  = request.getSettings();
      String           format    = settings.format();
      String           compression = settings.compression();
      boolean          copyFiles = "COPY".equals(settings.fileObjectPolicy());
      long             maxMillis = settings.integer("maxDurationMinutes", 240) * 60_000L;

      if("ZSTD".equals(compression) || "PARQUET".equals(format))
      {
         throw (new IllegalStateException("The " + ("ZSTD".equals(compression) ? "ZSTD compression" : "PARQUET format") + " is not available yet; use GZIP and JSONL or CSV."));
      }
      String sensitivePolicy = settings.string("sensitiveFieldPolicy", "EXCLUDE");
      if("INCLUDE_ENCRYPTED_ONLY".equals(sensitivePolicy) && !request.getTransform().encrypts())
      {
         throw (new IllegalStateException("The sensitive field policy INCLUDE_ENCRYPTED_ONLY requires client-side encryption."));
      }
      String consistency = consistencyFor(settings.string("consistency", "SNAPSHOT_PREFERRED"), result);
      result.getWarnings().forEach(progress::warn);
      catalog.update(BackupsTables.RUN, result.getRunId(), Map.of("consistencyAchieved", result.getConsistencyAchieved()));

      //////////////////////////////////////////////////////////////////////////////////////
      // object lock is applied at write time, to every object of the run (spec 10.3)     //
      //////////////////////////////////////////////////////////////////////////////////////
      String  lockMode    = settings.string("objectLockMode", "NONE");
      Instant retainUntil = null;
      if(!"NONE".equals(lockMode))
      {
         if(!storage.capabilities().contains(BackupStorage.StorageCapability.OBJECT_LOCK))
         {
            throw (new IllegalStateException("The plan asks for object lock " + lockMode + ", but the storage bucket does not support object lock."));
         }
         retainUntil = started.plus(java.time.Duration.ofDays(Math.max(1, settings.integer("objectLockDays", 1))));
      }
      else
      {
         lockMode = null;
      }
      final String  fileLockMode    = lockMode;
      final Instant fileRetainUntil = retainUntil;

      Function<String, BackupStorage> sourceResolver = request.getSourceResolver() != null ? request.getSourceResolver() : alias -> storage;
      FileCopier copier = new FileCopier(sourceResolver, request.getBucketAlias(), storage, instancePrefix + "_files/sha256/", request.getTransform(),
         catalog.fileStoreRegistry(request.getBucketAlias(), result.getRunId(), request.getEncryptionKeyFingerprint()), !copyFiles, result.getRunKey())
         .withObjectLock(lockMode, retainUntil);

      List<BackupManifest.TableEntry> manifestTables = new ArrayList<>();
      List<String>                    checksums      = new ArrayList<>();
      List<String>                    fingerprints   = new ArrayList<>();
      FileIndexWriter                 indexWriter    = null;
      long                            rows           = 0;
      long                            bytesStored    = 0;
      long                            bytesRaw       = 0;
      int                             done           = 0;
      int                             referenced     = 0;
      int                             copied         = 0;
      int                             deduplicated   = 0;
      int                             missing        = 0;
      long                            fileBytes      = 0;
      long                            fileBytesRef   = 0;

      progress.phase("tables").total(request.getScope().tables().size(), "tables").done(0).bytesDone(0);
      try(PendingFiles pendingFiles = new PendingFiles())
      {
         for(ScopeResolver.ResolvedTable resolved : request.getScope().tables())
         {
            checkJob();
            long tableStarted = System.nanoTime();
            long tableBytesBefore = bytesStored;
            progress.message("Backing up " + resolved.name() + " (" + (done + 1) + " of " + request.getScope().tables().size() + ")");
            QTableMetaData table = qInstance.getTable(resolved.name());
            String         backendType = qInstance.getBackend(table.getBackendName()) == null ? null : qInstance.getBackend(table.getBackendName()).getBackendType();

            List<BackupManifest.ExcludedField> excluded = excludedFields(table, resolved, sensitivePolicy, settings.string("heavyFieldPolicy", "INLINE"));
            List<String> excludedNames = excluded.stream().map(BackupManifest.ExcludedField::name).toList();

            ResumeState.TableState prior = request.getResume() == null ? null : request.getResume().table(table.getName());

            long[] tableWritten = { 0 };
            List<TableArtifactWriter> writers = new ArrayList<>();
            String tablePrefix = runPrefix + "tables/" + table.getName() + "/";
            Map<TableArtifactWriter, Integer> lastArtifactIds = new LinkedHashMap<>();

            List<FileReference> references = new ArrayList<>();
            long[]              counter    = { 0 };
            Instant             deadline   = started.plusMillis(maxMillis);
            String              rowsHash   = null;
            List<TableArtifactWriter.PartResult> allResults = new ArrayList<>();
            boolean wantsReferences = copyFiles || "REFERENCE_ONLY".equals(settings.fileObjectPolicy());

            if(prior != null && prior.complete())
            {
               ////////////////////////////////////////////////////////////////////////////////
               // resume: this table finished before the failure.  Keep its parts, and only  //
               // walk its rows again for the file references (the index is rebuilt)         //
               ////////////////////////////////////////////////////////////////////////////////
               if(wantsReferences)
               {
                  collectReferences(request, table, resolved.filterJson(), null, references);
               }
               allResults.addAll(prior.parts());
               rowsHash = prior.rowsHash();
               counter[0] = prior.rows();
            }
            else
            {
               for(String f : "JSONL_AND_CSV".equals(format) ? List.of("JSONL", "CSV") : List.of("CSV".equals(format) ? "CSV" : "JSONL"))
               {
                  TableArtifactWriter writer = new TableArtifactWriter(storage, tablePrefix, table, excludedNames, f, compression, settings.targetPartBytes(), MAX_ROWS_PER_PART,
                     request.getTransform(), result.getRunKey()).withObjectLock(fileLockMode, fileRetainUntil);
                  writer.withPartListener(part ->
                  {
                     try
                     {
                        tableWritten[0] += part.bytesStored();
                        progress.bytesDone(tableBytesBefore + tableWritten[0]);
                        progress.info("Wrote " + part.key().substring(runPrefix.length()));
                        lastArtifactIds.put(writer, catalog.insertArtifact(result.getRunId(), "TABLE_DATA", table.getName(), part.partNumber(), part.key().substring(runPrefix.length()),
                           part.format(), part.compression(), part.encrypted(), part.rows(), part.bytesRaw(), part.bytesStored(), part.sha256Stored(), part.sha256Raw(), null,
                           part.firstKey(), part.lastKey(), TableFingerprints.of(table, backendType)));
                     }
                     catch(Exception e)
                     {
                        throw (new IllegalStateException("Could not record a part of " + table.getName() + ": " + e.getMessage(), e));
                     }
                  });
                  writers.add(writer);
               }

               Serializable startAfter = null;
               if(prior != null && !prior.parts().isEmpty())
               {
                  boolean canContinue = writers.size() == 1 && "JSONL".equals(format) && !request.getTransform().encrypts();
                  if(canContinue)
                  {
                     TableArtifactWriter writer = writers.get(0);
                     tableWritten[0] = prior.parts().stream().mapToLong(TableArtifactWriter.PartResult::bytesStored).sum();
                     progress.bytesDone(tableBytesBefore + tableWritten[0]);
                     writer.resumeFrom(prior.parts());
                     seedRowsHash(storage, runPrefix, prior.parts(), writer);
                     lastArtifactIds.put(writer, prior.artifactIds().get(prior.artifactIds().size() - 1));
                     startAfter = ValueUtils.getValueAsFieldType(table.getField(table.getPrimaryKeyField()).getType(), prior.lastKey());
                     counter[0] = prior.rows();
                     if(wantsReferences)
                     {
                        collectReferences(request, table, resolved.filterJson(), prior.lastKey(), references);
                     }
                  }
                  else
                  {
                     /////////////////////////////////////////////////////////////////////////
                     // CSV, a second format or encryption cannot rebuild the rows hash from //
                     // the parts: start this table over (its part objects are overwritten)  //
                     /////////////////////////////////////////////////////////////////////////
                     catalog.deleteByIds(BackupsTables.ARTIFACT, prior.artifactIds());
                  }
               }

               request.getReader().read(table, resolved.filterJson(), startAfter, settings.pageSize(), null, record ->
               {
                  try
                  {
                     for(TableArtifactWriter writer : writers)
                     {
                        writer.write(record);
                     }
                  }
                  catch(IOException e)
                  {
                     throw (new IllegalStateException("Could not write a row of " + table.getName() + ": " + e.getMessage(), e));
                  }
                  if(wantsReferences)
                  {
                     for(FileReferenceProvider provider : request.getFileProviders())
                     {
                        references.addAll(provider.referencesIn(table, record));
                     }
                  }
                  counter[0]++;
                  if(counter[0] % settings.pageSize() == 0)
                  {
                     heartbeat(request, result, deadline, table.getName(), counter[0]);
                  }
               });

               for(TableArtifactWriter writer : writers)
               {
                  allResults.addAll(writer.finish());
                  rowsHash = writer.rowsHash();
                  Integer lastId = lastArtifactIds.get(writer);
                  if(lastId != null)
                  {
                     catalog.update(BackupsTables.ARTIFACT, lastId, Map.of("rowsHash", rowsHash));
                  }
               }
            }

            List<BackupManifest.Part> parts = new ArrayList<>();
            String fingerprint = TableFingerprints.of(table, backendType);
            fingerprints.add(fingerprint);
            for(TableArtifactWriter.PartResult part : allResults)
            {
               String relative = part.key().substring(runPrefix.length());
               parts.add(new BackupManifest.Part(relative, part.rows(), part.bytesRaw(), part.bytesStored(), part.sha256Stored(), part.sha256Raw(), part.format(), part.compression(), part.encrypted()));
               checksums.add(part.sha256Stored() + "  " + relative);
               bytesStored += part.bytesStored();
               bytesRaw += part.bytesRaw();
            }
            rows += counter[0];

            pendingFiles.write(references);

            Map<String, String> keyRange = new LinkedHashMap<>();
            keyRange.put("min", allResults.isEmpty() ? null : allResults.get(0).firstKey());
            keyRange.put("max", allResults.isEmpty() ? null : allResults.get(allResults.size() - 1).lastKey());
            manifestTables.add(new BackupManifest.TableEntry(table.getName(), resolved.role().name(), table.getBackendName(), table.getPrimaryKeyField(),
               fingerprint, TableFingerprints.CORE_ALGORITHM, TableFingerprints.descriptor(table, backendType), resolved.filterJson(), excluded, counter[0], rowsHash, keyRange, parts, consistency));
            done++;
            catalogProgress(request, result, done, rows, bytesRaw, bytesStored);
            progress.done(done).bytesDone(bytesStored);
            progress.info(String.format(Locale.US, "%s: %,d rows, %.1f MB in %.1f s", table.getName(), counter[0],
               (bytesStored - tableBytesBefore) / 1_000_000.0, (System.nanoTime() - tableStarted) / 1_000_000_000.0));
            if(request.getCancelRequested().getAsBoolean())
            {
               throw (new CancelledException());
            }
         }
         progress.phase("media").total(pendingFiles.count, "files").done(0).bytesDone(bytesStored);
         try(BufferedReader pending = pendingFiles.openReader())
         {
            String line;
            while((line = pending.readLine()) != null)
            {
               FileReference ref = MAPPER.readValue(line, FileReference.class);
               checkJob();
               if(indexWriter == null)
               {
                  indexWriter = new FileIndexWriter(storage, runPrefix + "files/index-00001.jsonl.gz", result.getRunKey(), fileLockMode, fileRetainUntil);
               }
               FileCopier.IndexEntry entry = copier.process(ref);
               indexWriter.write(entry);
               referenced++;
               switch(entry.status())
               {
                  case FileCopier.COPIED ->
                  {
                     copied++;
                     fileBytes += entry.size();
                  }
                  case FileCopier.DEDUPLICATED -> deduplicated++;
                  case FileCopier.MISSING -> missing++;
                  default -> fileBytesRef += Math.max(0, entry.size());
               }
               progress.done(referenced).bytesDone(bytesStored + fileBytes)
                  .message("Media: " + copied + " copied, " + deduplicated + " already stored (" + referenced + " of " + pendingFiles.count + ")");
               if(request.getCancelRequested().getAsBoolean())
               {
                  throw (new CancelledException());
               }
            }
         }

         progress.info("Media: " + referenced + " files, " + copied + " copied, " + deduplicated + " already stored, " + missing + " missing");
      }
      finally
      {
         if(indexWriter != null)
         {
            indexWriter.close();
         }
      }

      checkJob();
      progress.phase("upload").message("Writing catalog and manifest");
      List<String> indexKeys = new ArrayList<>();
      if(indexWriter != null)
      {
         indexKeys.add("files/index-00001.jsonl.gz");
         progress.info("Wrote files/index-00001.jsonl.gz");
         checksums.add(indexWriter.sha256Stored() + "  files/index-00001.jsonl.gz");
         catalog.insertArtifact(result.getRunId(), "FILE_OBJECTS_INDEX", null, 1, "files/index-00001.jsonl.gz", "JSONL", "GZIP", request.getTransform().encrypts(),
            indexWriter.rows(), indexWriter.bytesRaw(), indexWriter.bytesStored(), indexWriter.sha256Stored(), indexWriter.sha256Raw(), null, null, null, null);
         bytesStored += indexWriter.bytesStored();
      }

      if(missing > 0)
      {
         if("FAIL".equals(settings.string("fileMissingPolicy", "WARN")))
         {
            throw (new IllegalStateException(missing + " referenced files are missing at the source"));
         }
         String warning = missing + " referenced files were missing at the source";
         result.getWarnings().add(warning);
         progress.warn(warning);
      }

      /////////////////////////////////////////////////////////
      // checksums, then the manifest LAST (the commit mark) //
      /////////////////////////////////////////////////////////
      byte[] checksumBytes = (String.join("\n", checksums) + "\n").getBytes(StandardCharsets.UTF_8);
      putBytes(storage, runPrefix + "checksums.sha256", checksumBytes, result.getRunKey(), lockMode, retainUntil);
      progress.info("Wrote checksums.sha256");
      catalog.insertArtifact(result.getRunId(), "CHECKSUMS", null, 1, "checksums.sha256", "TEXT", "NONE", false, checksums.size(), checksumBytes.length,
         checksumBytes.length, HashingOutputStream.sha256Hex(checksumBytes), HashingOutputStream.sha256Hex(checksumBytes), null, null, null, null);

      Instant finished = request.getClock().instant();
      List<String> order = request.getScope().names();
      BackupManifest manifest = new BackupManifest(BackupManifest.CURRENT_VERSION, result.getRunKey(),
         new BackupManifest.PlanInfo(settings.planKey(), request.getPlanName(), settings.manifestSettings()),
         new BackupManifest.InstanceInfo(request.getInstanceName(), request.getAppVersion(), qqqVersion(), List.of()),
         started.toString(), finished.toString(), request.getRequestedBy(), request.getSecurityScope(),
         new BackupManifest.ConsistencyInfo(result.getConsistencyAchieved(), List.of()),
         request.getTransform().encrypts()
            ? new BackupManifest.EncryptionInfo("CLIENT_AES256_GCM", "tink-streaming-aead-AES256_GCM_HKDF_1MB", request.getEncryptionKeyRef(), request.getEncryptionKeyFingerprint())
            : new BackupManifest.EncryptionInfo("NONE", null, null, null),
         manifestTables, order,
         new BackupManifest.FilesInfo(settings.fileObjectPolicy(), indexKeys, copied + deduplicated, fileBytes + fileBytesRef, missing),
         result.getWarnings(),
         new BackupManifest.Totals(manifestTables.size(), rows, bytesStored));
      byte[] manifestBytes = manifest.toJsonBytes();
      String manifestKey   = runPrefix + "manifest.json";
      putBytes(storage, manifestKey, manifestBytes, result.getRunKey(), lockMode, retainUntil);
      if(storage.head(manifestKey).isEmpty())
      {
         throw (new IllegalStateException("The manifest was written but is not visible in the bucket"));
      }
      progress.info("Wrote manifest.json");
      catalog.insertArtifact(result.getRunId(), "MANIFEST", null, 1, "manifest.json", "JSON", "NONE", false, manifestTables.size(), manifestBytes.length,
         manifestBytes.length, HashingOutputStream.sha256Hex(manifestBytes), HashingOutputStream.sha256Hex(manifestBytes), null, null, null, null);

      result.setManifestKey(manifestKey);
      result.setManifestSha256(HashingOutputStream.sha256Hex(manifestBytes));
      result.setTables(manifestTables.size());
      result.setRows(rows);
      result.setBytesRaw(bytesRaw);
      result.setBytesStored(bytesStored);
      result.setFilesReferenced(referenced);
      result.setFilesCopied(copied);
      result.setFilesDeduplicated(deduplicated);
      result.setFilesMissing(missing);
      result.setFileBytesCopied(fileBytes);

      Map<String, Serializable> extra = new LinkedHashMap<>();
      extra.put("manifestKey", manifestKey);
      extra.put("manifestSha256", result.getManifestSha256());
      extra.put("schemaFingerprint", TableFingerprints.combine(fingerprints));
      extra.put("qqqVersion", qqqVersion());
      extra.put("consistencyAchieved", result.getConsistencyAchieved());
      extra.put("filesReferenced", referenced);
      extra.put("filesCopied", copied);
      extra.put("filesDeduplicated", deduplicated);
      extra.put("filesMissing", missing);
      extra.put("fileBytesCopied", fileBytes);
      extra.put("fileBytesReferenced", fileBytesRef);
      extra.put("lockedUntil", retainUntil);
      extra.put("tablesDone", manifestTables.size());
      extra.put("rowsTotal", rows);
      extra.put("bytesRaw", bytesRaw);
      extra.put("bytesStored", bytesStored);
      extra.put("warningCount", result.getWarnings().size());
      finishWith(request, result, result.getWarnings().isEmpty() ? "SUCCEEDED" : "SUCCEEDED_WITH_WARNINGS", null, started, extra);
   }



   /*******************************************************************************
    ** Declare the work before scope resolution. No byte estimate requires a scan.
    *******************************************************************************/
   public static void prepare(JobProgress progress)
   {
      progress.phases(JobPhase.of("prepare", "Resolve scope", 2), JobPhase.of("tables", "Back up tables", 75),
         JobPhase.of("media", "Copy media files", 18), JobPhase.of("upload", "Write catalog and manifest", 5));
      progress.phase("prepare").message("Resolving scope");
   }



   /*******************************************************************************
    ** Complete or fail the current phase; the core has no phase-finish method.
    *******************************************************************************/
   private static void completePhase(JobProgress progress, JobPhase.Status status)
   {
      progress.getSnapshot().getPhases().stream().filter(p -> p.getStatus() == JobPhase.Status.RUNNING).forEach(p -> p.setStatus(status));
      progress.flush();
   }



   /*******************************************************************************
    ** Log only a controlled failure description, never provider or row text.
    *******************************************************************************/
   private static void failProgress(JobProgress progress, String message)
   {
      completePhase(progress, JobPhase.Status.FAILED);
      progress.error(message);
   }



   /*******************************************************************************
    ** Bridge checked job interrupts through the row consumer, preserving the cause
    ** so execute can propagate it back to the durable worker.
    *******************************************************************************/
   private static void checkJob()
   {
      try
      {
         JobChunkScope.checkBetweenPages();
      }
      catch(QException e)
      {
         throw (new IllegalStateException(e));
      }
   }



   /*******************************************************************************
    ** Work out the consistency achieved.  Until the core consistent-read hook ships
    ** (QQQ core PR feature/consistent-read-transactions, next 4.1.x RC), no backend
    ** reports a snapshot, so: SNAPSHOT_REQUIRED fails before writing anything,
    ** SNAPSHOT_PREFERRED records BEST_EFFORT with a warning, PER_TABLE records PER_TABLE.
    *******************************************************************************/
   private String consistencyFor(String requested, BackupRunResult result)
   {
      switch(requested)
      {
         case "SNAPSHOT_REQUIRED":
            throw (new IllegalStateException("The plan requires a consistent snapshot, which this version of QQQ cannot open yet. Use SNAPSHOT_PREFERRED or PER_TABLE."));
         case "PER_TABLE":
            result.setConsistencyAchieved("PER_TABLE");
            return ("PER_TABLE");
         default:
            result.setConsistencyAchieved("BEST_EFFORT");
            result.getWarnings().add("No consistent snapshot was available: every table was read on its own (BEST_EFFORT)");
            return ("BEST_EFFORT");
      }
   }



   /*******************************************************************************
    ** The fields left out of a table, by policy.  The primary key is never left out.
    *******************************************************************************/
   private List<BackupManifest.ExcludedField> excludedFields(QTableMetaData table, ScopeResolver.ResolvedTable resolved, String sensitivePolicy, String heavyPolicy)
   {
      Map<String, String> out = new LinkedHashMap<>();
      for(QFieldMetaData field : table.getFields().values())
      {
         if(field.getName().equals(table.getPrimaryKeyField()))
         {
            continue;
         }
         if("EXCLUDE".equals(sensitivePolicy) && (field.getIsSensitive() || field.getType() == QFieldType.PASSWORD))
         {
            out.put(field.getName(), "SENSITIVE_POLICY");
         }
         else if("EXCLUDE".equals(heavyPolicy) && field.getIsHeavy())
         {
            out.put(field.getName(), "HEAVY_POLICY");
         }
      }
      for(String name : resolved.excludedFields())
      {
         if(table.getFields().containsKey(name) && !name.equals(table.getPrimaryKeyField()))
         {
            out.put(name, "SCOPE");
         }
      }
      return (out.entrySet().stream().map(e -> new BackupManifest.ExcludedField(e.getKey(), e.getValue())).toList());
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void heartbeat(BackupRunRequest request, BackupRunResult result, Instant deadline, String tableName, long rowsSoFar)
   {
      checkJob();
      Instant now = request.getClock().instant();
      if(request.getCancelRequested().getAsBoolean())
      {
         throw (new CancelledException());
      }
      if(now.isAfter(deadline))
      {
         throw (new TimedOutException("The run exceeded its maximum duration"));
      }
      try
      {
         catalog.update(BackupsTables.RUN, result.getRunId(), Map.of("heartbeatDate", now));
      }
      catch(Exception e)
      {
         LOG.warn("Could not record a heartbeat", e);
         request.getProgress().warn("Could not record a heartbeat");
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void catalogProgress(BackupRunRequest request, BackupRunResult result, int done, long rows, long bytesRaw, long bytesStored)
   {
      try
      {
         Map<String, Serializable> v = new LinkedHashMap<>();
         v.put("tablesDone", done);
         v.put("rowsTotal", rows);
         v.put("bytesRaw", bytesRaw);
         v.put("bytesStored", bytesStored);
         catalog.update(BackupsTables.RUN, result.getRunId(), v);
      }
      catch(Exception e)
      {
         LOG.warn("Could not record progress", e);
         request.getProgress().warn("Could not record progress");
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void finish(BackupRunRequest request, BackupRunResult result, String status, String message, Instant started)
   {
      try
      {
         finishWith(request, result, status, message, started, new LinkedHashMap<>());
      }
      catch(Exception e)
      {
         LOG.warn("Could not finish the run record", e);
         request.getProgress().warn("Could not finish the run record");
         result.setStatus(status);
         result.setErrorMessage(message);
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void finishWith(BackupRunRequest request, BackupRunResult result, String status, String message, Instant started, Map<String, Serializable> extra) throws Exception
   {
      Instant end = request.getClock().instant();
      Map<String, Serializable> v = new LinkedHashMap<>(extra);
      v.put("status", status);
      v.put("endDate", end);
      v.put("durationMs", end.toEpochMilli() - started.toEpochMilli());
      v.put("heartbeatDate", end);
      v.put("errorMessage", message);
      v.put("warningCount", result.getWarnings().size());
      catalog.update(BackupsTables.RUN, result.getRunId(), v);
      result.setStatus(status);
      result.setErrorMessage(message);
      signal(request, result, status, message);
   }



   /*******************************************************************************
    ** Update the plan denormalized fields, check in the heartbeat, and raise or resolve alerts.
    *******************************************************************************/
   private void signal(BackupRunRequest request, BackupRunResult result, String status, String message) throws Exception
   {
      Integer planId = request.getSettings().getPlanId();
      if(planId == null)
      {
         return;
      }
      boolean ok = "SUCCEEDED".equals(status) || "SUCCEEDED_WITH_WARNINGS".equals(status);
      Map<String, Serializable> plan = new LinkedHashMap<>();
      plan.put("lastRunId", result.getRunId());
      if(ok)
      {
         plan.put("lastSuccessRunId", result.getRunId());
         plan.put("lastSuccessDate", request.getClock().instant());
         plan.put("slaStatus", "OK");
      }
      catalog.update(BackupsTables.PLAN, planId, plan);

      BackupAlertSink sink = BackupsRuntime.getAlertSink();
      String planKey = request.getSettings().planKey();
      BackupEvents.runFinished(planKey, result.getRunKey(), status, result.getRows(), result.getBytesStored());
      if(ok)
      {
         sink.resolve("backups.run.failed", "backups.run.failed:" + planKey);
         sink.heartbeat("backups.plan:" + planKey, request.getSettings().integer("slaHours", 26) * 3600L, 0L);
         if(result.getFilesMissing() > 0)
         {
            sink.raise("backups.files.missing", "backups.files.missing:" + planKey, BackupAlertSink.Severity.WARNING, "Referenced files are missing",
               Map.of("plan", planKey, "missing", String.valueOf(result.getFilesMissing()), "run", result.getRunKey()));
         }
         else
         {
            sink.resolve("backups.files.missing", "backups.files.missing:" + planKey);
         }
      }
      else if(!"CANCELLED".equals(status))
      {
         sink.raise("backups.run.failed", "backups.run.failed:" + planKey, BackupAlertSink.Severity.WARNING, "A backup run failed",
            Map.of("plan", planKey, "status", status, "run", result.getRunKey()));
      }
   }



   /*******************************************************************************
    ** Provider exceptions may contain credentials or row values. Persist only the
    ** failure type; detailed diagnostics stay in the server log.
    *******************************************************************************/
   private static String scrub(Exception e)
   {
      return ("Backup failed (" + e.getClass().getSimpleName() + "); see server logs for details.");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void putBytes(BackupStorage storage, String key, byte[] bytes, String runKey, String lockMode, Instant retainUntil) throws IOException
   {
      WriteOptions options = WriteOptions.create().withMetadata("qqq-backup-run", runKey);
      if(lockMode != null)
      {
         options.withObjectLock(lockMode, retainUntil);
      }
      try(OutputStream out = storage.openWrite(key, options))
      {
         out.write(bytes);
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String trimSlashes(String s)
   {
      String r = s;
      while(r.startsWith("/"))
      {
         r = r.substring(1);
      }
      while(r.endsWith("/"))
      {
         r = r.substring(0, r.length() - 1);
      }
      return (r);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static String qqqVersion()
   {
      String v = QInstance.class.getPackage().getImplementationVersion();
      return (v == null ? "unknown" : v);
   }



   /*******************************************************************************
    ** Walk a table's rows only for the file references they hold (resume: rows that
    ** an earlier attempt already wrote to parts).  Stops after stopAfterKey (inclusive)
    ** when given.
    *******************************************************************************/
   private void collectReferences(BackupRunRequest request, QTableMetaData table, String filterJson, String stopAfterKey, List<FileReference> references) throws Exception
   {
      String pk = table.getPrimaryKeyField();
      try
      {
         request.getReader().read(table, filterJson, null, request.getSettings().pageSize(), null, record ->
         {
            if(stopAfterKey != null && !stopAfterKey.equals(String.valueOf(record.getValue(pk))) && keyAfter(record.getValue(pk), stopAfterKey))
            {
               throw (new StopReadingException());
            }
            checkJob();
            if(request.getCancelRequested().getAsBoolean())
            {
               throw (new CancelledException());
            }
            for(FileReferenceProvider provider : request.getFileProviders())
            {
               references.addAll(provider.referencesIn(table, record));
            }
         });
      }
      catch(StopReadingException e)
      {
         // done: we are past the last key of the written parts
      }
   }



   /*******************************************************************************
    ** Whether a primary key value sorts after a key given as text.
    *******************************************************************************/
   @SuppressWarnings({ "unchecked", "rawtypes" })
   private static boolean keyAfter(Serializable value, String lastKeyText)
   {
      if(value instanceof Number number)
      {
         return (new java.math.BigDecimal(number.toString()).compareTo(new java.math.BigDecimal(lastKeyText)) > 0);
      }
      return (String.valueOf(value).compareTo(lastKeyText) > 0);
   }



   /*******************************************************************************
    ** Feed the rows hash of a resumed table from the parts already in storage (JSONL,
    ** gzip or none, not encrypted: the decoded bytes are the canonical lines).
    *******************************************************************************/
   private static void seedRowsHash(BackupStorage storage, String runPrefix, List<TableArtifactWriter.PartResult> parts, TableArtifactWriter writer) throws IOException
   {
      byte[] buffer = new byte[65536];
      for(TableArtifactWriter.PartResult part : parts)
      {
         try(java.io.InputStream raw = storage.openRead(part.key()); java.io.InputStream in = "GZIP".equals(part.compression()) ? new java.util.zip.GZIPInputStream(raw) : raw)
         {
            int n;
            while((n = in.read(buffer)) >= 0)
            {
               writer.seedRowsHash(buffer, 0, n);
            }
         }
      }
   }



   /***************************************************************************
    ** Thrown from a row sink to stop a reference-only walk.
    ***************************************************************************/
   static class StopReadingException extends RuntimeException
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   static class CancelledException extends RuntimeException
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   static class TimedOutException extends RuntimeException
   {
      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      TimedOutException(String message)
      {
         super(message);
      }
   }



   /***************************************************************************
    ** Spool references locally between table export and media copy. Keeping the
    ** whole run's references in heap would make memory grow with every table.
    ** The temporary file is removed on success, failure and cancellation.
    ***************************************************************************/
   private static class PendingFiles implements AutoCloseable
   {
      private final Path path;
      private final BufferedWriter writer;
      private long count;



      /*******************************************************************************
       ** Create a private temporary spool, outside the stored backup artifacts.
       *******************************************************************************/
      PendingFiles() throws IOException
      {
         path = Files.createTempFile("qqq-backup-references-", ".jsonl");
         writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
      }



      /*******************************************************************************
       ** Append this table's references without retaining earlier tables in memory.
       *******************************************************************************/
      void write(List<FileReference> references) throws IOException
      {
         for(FileReference reference : references)
         {
            writer.write(MAPPER.writeValueAsString(reference));
            writer.newLine();
            count++;
         }
      }



      /*******************************************************************************
       ** Finish the spool and stream it back during the media phase.
       *******************************************************************************/
      BufferedReader openReader() throws IOException
      {
         writer.close();
         return (Files.newBufferedReader(path, StandardCharsets.UTF_8));
      }



      /*******************************************************************************
       ** Always release the local references, including on exceptions.
       *******************************************************************************/
      @Override
      public void close() throws IOException
      {
         try
         {
            writer.close();
         }
         finally
         {
            Files.deleteIfExists(path);
         }
      }
   }



   /***************************************************************************
    ** The per-run file index, written as it goes: one JSON line per reference.
    ***************************************************************************/
   static class FileIndexWriter
   {
      private final HashingOutputStream raw;
      private final HashingOutputStream stored;
      private long                      rows = 0;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      FileIndexWriter(BackupStorage storage, String key, String runKey, String lockMode, Instant retainUntil) throws IOException
      {
         WriteOptions options = WriteOptions.create().withMetadata("qqq-backup-run", runKey);
         if(lockMode != null)
         {
            options.withObjectLock(lockMode, retainUntil);
         }
         stored = new HashingOutputStream(storage.openWrite(key, options));
         raw = new HashingOutputStream(new GZIPOutputStream(stored));
      }



      /*******************************************************************************
       ** Write one entry.
       *******************************************************************************/
      void write(FileCopier.IndexEntry entry) throws IOException
      {
         Map<String, Object> line = new LinkedHashMap<>();
         line.put("table", entry.tableName());
         line.put("recordKey", entry.recordKey());
         line.put("field", entry.field());
         line.put("sourceBucket", entry.sourceBucketAlias());
         line.put("sourceKey", entry.sourceKey());
         line.put("sha256", entry.sha256());
         line.put("size", entry.size());
         line.put("status", entry.status());
         line.put("casKey", entry.casKey());
         raw.write((MAPPER.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8));
         rows++;
      }



      /*******************************************************************************
       ** Close the stream.
       *******************************************************************************/
      void close() throws IOException
      {
         raw.close();
      }



      /*******************************************************************************
       ** Rows written.
       *******************************************************************************/
      long rows()
      {
         return (rows);
      }



      /*******************************************************************************
       ** Raw byte count.
       *******************************************************************************/
      long bytesRaw()
      {
         return (raw.getCount());
      }



      /*******************************************************************************
       ** Stored byte count.
       *******************************************************************************/
      long bytesStored()
      {
         return (stored.getCount());
      }



      /*******************************************************************************
       ** Raw hash.
       *******************************************************************************/
      String sha256Raw()
      {
         return (raw.getSha256());
      }



      /*******************************************************************************
       ** Stored hash.
       *******************************************************************************/
      String sha256Stored()
      {
         return (stored.getSha256());
      }
   }
}
