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

package com.kingsrook.qbits.backups.prune;


import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qqq.backend.core.actions.audits.AuditAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Prune per retention (spec 10.3, 6.4, 8.5): plans what to delete without writing
 ** anything (the Review step), and carries it out (the Result step).
 **
 ** - Runs are classified (backups_run.retentionClass) by the GFS PruneCalculator,
 **   and the runs that are not kept have every object deleted through
 **   BackupStorage.deletePermanent on the plan prune alias (pruneBucketAlias; the
 **   backup alias when none is set).  The run row is kept as history, with
 **   prunedDate set.
 ** - A run with an object that is still locked is not touched: it is reported as
 **   "kept: locked until ..." and tried again at the next prune.
 ** - Prefixes of the plan with no manifest, whose newest object is older than 7
 **   days, are debris and are always deleted.
 ** - The content-addressed file store is mark-and-sweep: the file indexes of the
 **   retained runs give each backups_file_object its referenceCount, and objects
 **   nothing retained references (and that are not locked) are deleted, with
 **   deletedDate set.  Nothing is swept while a backup is running or when any index
 **   cannot be read: no deletion on uncertainty.
 *******************************************************************************/
public class BackupPruner
{
   private static final QLogger       LOG    = QLogger.getLogger(BackupPruner.class);
   private static final ObjectMapper  MAPPER = new ObjectMapper();

   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    ** One run, with what it holds in storage.
    *******************************************************************************/
   public record RunPlan(Integer runId, String runKey, String runPrefix, PruneCalculator.Decision decision, List<StoredObjectInfo> objects, long bytes, Instant lockedUntil)
   {
      /*******************************************************************************
       ** Whether the run is to be deleted (not kept, and nothing locked).
       *******************************************************************************/
      public boolean toDelete()
      {
         return (!decision.keep() && lockedUntil == null);
      }



      /*******************************************************************************
       ** Whether the run is not kept by retention but cannot be deleted yet.
       *******************************************************************************/
      public boolean lockedNotKept()
      {
         return (!decision.keep() && lockedUntil != null);
      }
   }



   /*******************************************************************************
    ** A prefix with no manifest, older than 7 days.
    *******************************************************************************/
   public record DebrisPlan(String prefix, Integer runId, List<StoredObjectInfo> objects, long bytes, Instant lockedUntil)
   {
   }



   /*******************************************************************************
    ** One content-addressed object that nothing retained references.
    *******************************************************************************/
   public record SweepCandidate(Integer fileObjectId, String sha256, String objectKey, long size, Instant lockedUntil)
   {
   }



   /*******************************************************************************
    ** The mark-and-sweep of the file store.
    **
    ** @param deferredReason why nothing will be swept, or null
    ** @param referenceCounts the new referenceCount of each live file object, by id
    ** @param candidates the unreferenced objects
    *******************************************************************************/
   public record SweepPlan(String deferredReason, Map<Integer, Integer> referenceCounts, List<SweepCandidate> candidates)
   {
      /*******************************************************************************
       ** Candidates that will be deleted (not locked).
       *******************************************************************************/
      public List<SweepCandidate> deletable()
      {
         return (candidates.stream().filter(c -> c.lockedUntil() == null).toList());
      }



      /*******************************************************************************
       ** Candidates still locked.
       *******************************************************************************/
      public List<SweepCandidate> locked()
      {
         return (candidates.stream().filter(c -> c.lockedUntil() != null).toList());
      }
   }



   /*******************************************************************************
    ** The whole plan for one backup plan.
    *******************************************************************************/
   public record PrunePlan(Integer planId, String planKey, List<RunPlan> runs, List<DebrisPlan> debris, SweepPlan sweep)
   {
      /*******************************************************************************
       ** Runs to delete.
       *******************************************************************************/
      public List<RunPlan> toDelete()
      {
         return (runs.stream().filter(RunPlan::toDelete).toList());
      }



      /*******************************************************************************
       ** Runs not kept by retention but locked.
       *******************************************************************************/
      public List<RunPlan> lockedNotKept()
      {
         return (runs.stream().filter(RunPlan::lockedNotKept).toList());
      }



      /*******************************************************************************
       ** The kept runs by class.
       *******************************************************************************/
      public Map<PruneCalculator.RetentionClass, Integer> keptByClass()
      {
         return (PruneCalculator.keptByClass(runs.stream().map(RunPlan::decision).toList()));
      }



      /*******************************************************************************
       ** How many runs are kept only because a guard says so (minimum, verified, restore).
       *******************************************************************************/
      public long keptByGuard()
      {
         return (runs.stream().filter(r -> r.decision().keep() && !r.decision().guards().isEmpty()).count());
      }



      /*******************************************************************************
       ** Bytes that will be freed.
       *******************************************************************************/
      public long bytesToFree()
      {
         return (toDelete().stream().mapToLong(RunPlan::bytes).sum()
            + debris.stream().filter(d -> d.lockedUntil() == null).mapToLong(DebrisPlan::bytes).sum()
            + sweep.deletable().stream().mapToLong(SweepCandidate::size).sum());
      }
   }



   /*******************************************************************************
    ** What a prune did.
    *******************************************************************************/
   public record Outcome(int runsDeleted, int runsLocked, int debrisPrefixesDeleted, int debrisLocked, int filesSwept, int filesLocked, long bytesFreed, List<String> messages)
   {
   }



   /*******************************************************************************
    ** Work out what a prune would do, writing nothing.
    *******************************************************************************/
   public PrunePlan plan(QRecord plan) throws QException
   {
      Instant       now        = BackupsRuntime.getClock().instant();
      Integer       planId     = plan.getValueInteger("id");
      String        planKey    = plan.getValueString("planKey");
      BackupStorage deleteSide = BackupsRuntime.storageFor(pruneAlias(plan));

      List<QRecord> allRuns = catalog.where(BackupsTables.RUN, "backupPlanId", planId);

      /////////////////
      // GFS classes //
      /////////////////
      Set<Integer> protectedByRestore = restoreProtected(now);
      List<PruneCalculator.RunInfo> infos = new ArrayList<>();
      Map<Integer, QRecord> usable = new LinkedHashMap<>();
      for(QRecord run : allRuns)
      {
         if(run.getValue("prunedDate") == null && isSuccessful(run))
         {
            usable.put(run.getValueInteger("id"), run);
            infos.add(new PruneCalculator.RunInfo(run.getValueInteger("id"), startOf(run), isVerified(run), protectedByRestore.contains(run.getValueInteger("id"))));
         }
      }
      ZoneId zone = zoneOf(plan);
      List<PruneCalculator.Decision> decisions = PruneCalculator.calculate(infos, new PruneCalculator.Policy(intOf(plan, "retainDaily", 7),
         intOf(plan, "retainWeekly", 4), intOf(plan, "retainMonthly", 12), intOf(plan, "retainYearly", 0), intOf(plan, "retainMinimum", 3)), zone);

      List<RunPlan> runPlans = new ArrayList<>();
      for(PruneCalculator.Decision decision : decisions)
      {
         QRecord                run     = usable.get(decision.id());
         List<StoredObjectInfo> objects = decision.keep() ? List.of() : deleteSide.list(run.getValueString("runPrefix"));
         runPlans.add(new RunPlan(decision.id(), run.getValueString("runKey"), run.getValueString("runPrefix"), decision, objects,
            objects.stream().mapToLong(StoredObjectInfo::size).sum(), latestLock(objects, now)));
      }

      Set<Integer> deleting = new HashSet<>();
      runPlans.stream().filter(RunPlan::toDelete).forEach(r -> deleting.add(r.runId()));

      return (new PrunePlan(planId, planKey, runPlans, debris(plan, allRuns, deleteSide, now), sweepPlan(plan, deleting, deleteSide, now)));
   }



   /*******************************************************************************
    ** Carry out a prune.
    *******************************************************************************/
   public Outcome prune(QRecord plan, String prunedBy) throws QException
   {
      Instant       now        = BackupsRuntime.getClock().instant();
      BackupStorage deleteSide = BackupsRuntime.storageFor(pruneAlias(plan));
      PrunePlan     prunePlan  = plan(plan);
      List<String>  messages   = new ArrayList<>();

      int  runsDeleted = 0;
      int  runsLocked  = 0;
      long bytesFreed  = 0;

      ///////////////////////////////////////////////////////////////////////
      // classify every run (the label tells why it is, or was, kept)      //
      ///////////////////////////////////////////////////////////////////////
      for(RunPlan run : prunePlan.runs())
      {
         Map<String, Serializable> values = new LinkedHashMap<>();
         values.put("retentionClass", run.decision().keep() ? run.decision().retentionClass().name() : "NONE");
         if(run.lockedUntil() != null)
         {
            values.put("lockedUntil", run.lockedUntil());
         }
         catalog.update(BackupsTables.RUN, run.runId(), values);
      }

      for(RunPlan run : prunePlan.runs())
      {
         if(run.decision().keep())
         {
            continue;
         }
         if(run.lockedUntil() != null)
         {
            runsLocked++;
            messages.add("Run " + run.runKey() + " kept: locked until " + run.lockedUntil());
            continue;
         }

         Instant lockedLate = deleteAll(deleteSide, run.objects(), now);
         if(lockedLate != null)
         {
            runsLocked++;
            messages.add("Run " + run.runKey() + " kept: locked until " + lockedLate);
            continue;
         }
         Map<String, Serializable> done = new LinkedHashMap<>();
         done.put("prunedDate", now);
         done.put("prunedByUserId", prunedBy);
         done.put("retentionClass", "NONE");
         catalog.update(BackupsTables.RUN, run.runId(), done);
         runsDeleted++;
         bytesFreed += run.bytes();
      }

      //////////////////////////////////
      // debris is always deleted     //
      //////////////////////////////////
      int debrisDeleted = 0;
      int debrisLocked  = 0;
      for(DebrisPlan debris : prunePlan.debris())
      {
         if(debris.lockedUntil() != null)
         {
            debrisLocked++;
            messages.add("Debris " + debris.prefix() + " kept: locked until " + debris.lockedUntil());
            continue;
         }
         Instant lockedLate = deleteAll(deleteSide, debris.objects(), now);
         if(lockedLate != null)
         {
            debrisLocked++;
            messages.add("Debris " + debris.prefix() + " kept: locked until " + lockedLate);
            continue;
         }
         debrisDeleted++;
         bytesFreed += debris.bytes();
         if(debris.runId() != null)
         {
            catalog.update(BackupsTables.RUN, debris.runId(), Map.of("prunedDate", now, "prunedByUserId", prunedBy));
         }
      }

      ////////////////////////////////////////////////////////////////////////
      // the file store: planned again, after the runs went, then swept      //
      ////////////////////////////////////////////////////////////////////////
      SweepPlan sweep      = sweepPlan(plan, Set.of(), deleteSide, now);
      int       filesSwept = 0;
      int       filesLocked = 0;
      for(Map.Entry<Integer, Integer> entry : sweep.referenceCounts().entrySet())
      {
         catalog.update(BackupsTables.FILE_OBJECT, entry.getKey(), Map.of("referenceCount", entry.getValue()));
      }
      if(sweep.deferredReason() != null)
      {
         messages.add("File store sweep skipped: " + sweep.deferredReason());
      }
      for(SweepCandidate candidate : sweep.candidates())
      {
         BackupStorage.DeleteResult result = candidate.lockedUntil() != null ? BackupStorage.DeleteResult.LOCKED : deleteSide.deletePermanent(candidate.objectKey());
         if(result == BackupStorage.DeleteResult.LOCKED)
         {
            filesLocked++;
            Instant until = candidate.lockedUntil() != null ? candidate.lockedUntil() : deleteSide.head(candidate.objectKey()).map(StoredObjectInfo::lockedUntil).orElse(null);
            if(until != null)
            {
               catalog.update(BackupsTables.FILE_OBJECT, candidate.fileObjectId(), Map.of("lockedUntil", until));
            }
            messages.add("File " + candidate.sha256().substring(0, Math.min(12, candidate.sha256().length())) + " kept: locked until " + until);
         }
         else
         {
            filesSwept++;
            bytesFreed += candidate.size();
            catalog.update(BackupsTables.FILE_OBJECT, candidate.fileObjectId(), Map.of("deletedDate", now, "referenceCount", 0));
         }
      }

      audit(plan, prunedBy, runsDeleted, debrisDeleted, filesSwept, bytesFreed);
      return (new Outcome(runsDeleted, runsLocked, debrisDeleted, debrisLocked, filesSwept, filesLocked, bytesFreed, messages));
   }



   /*******************************************************************************
    ** Delete objects, the manifest last (so a half-deleted run is not mistaken for
    ** a usable one).  Returns the latest lock date when something turned out to be
    ** locked after all, else null.
    *******************************************************************************/
   private Instant deleteAll(BackupStorage storage, List<StoredObjectInfo> objects, Instant now)
   {
      List<StoredObjectInfo> ordered = new ArrayList<>(objects);
      ordered.sort(Comparator.comparing((StoredObjectInfo o) -> o.key().endsWith("/manifest.json")).thenComparing(StoredObjectInfo::key));
      Instant locked = null;
      for(StoredObjectInfo object : ordered)
      {
         BackupStorage.DeleteResult result = storage.deletePermanent(object.key());
         if(result == BackupStorage.DeleteResult.LOCKED)
         {
            Instant until = storage.head(object.key()).map(StoredObjectInfo::lockedUntil).orElse(now);
            if(locked == null || (until != null && until.isAfter(locked)))
            {
               locked = until == null ? now : until;
            }
         }
      }
      return (locked);
   }



   /*******************************************************************************
    ** Prefixes of the plan that never got a manifest and are old enough.
    *******************************************************************************/
   private List<DebrisPlan> debris(QRecord plan, List<QRecord> allRuns, BackupStorage deleteSide, Instant now) throws QException
   {
      String prefix = planPrefix(plan);
      Map<String, List<StoredObjectInfo>> groups = new LinkedHashMap<>();
      for(StoredObjectInfo object : deleteSide.list(prefix))
      {
         String[] parts = object.key().substring(prefix.length()).split("/", 5);
         if(parts.length < 5)
         {
            continue;
         }
         groups.computeIfAbsent(prefix + parts[0] + "/" + parts[1] + "/" + parts[2] + "/" + parts[3] + "/", k -> new ArrayList<>()).add(object);
      }

      Map<String, QRecord> runsByPrefix = new LinkedHashMap<>();
      for(QRecord run : allRuns)
      {
         if(run.getValueString("runPrefix") != null)
         {
            runsByPrefix.put(run.getValueString("runPrefix"), run);
         }
      }

      List<DebrisPlan> debris = new ArrayList<>();
      for(Map.Entry<String, List<StoredObjectInfo>> group : groups.entrySet())
      {
         List<StoredObjectInfo> objects = group.getValue();
         if(objects.stream().anyMatch(o -> o.key().equals(group.getKey() + "manifest.json")))
         {
            continue;
         }
         QRecord run = runsByPrefix.get(group.getKey());
         if(run != null && (List.of("RUNNING", "QUEUED", "CANCELLING").contains(run.getValueString("status")) && isLive(run, now)))
         {
            continue;
         }
         Instant newest = objects.stream().map(StoredObjectInfo::lastModified).max(Comparator.naturalOrder()).orElse(now);
         if(newest.isAfter(now.minus(Duration.ofDays(PruneCalculator.DEBRIS_DAYS))))
         {
            continue;
         }
         debris.add(new DebrisPlan(group.getKey(), run == null ? null : run.getValueInteger("id"), objects, objects.stream().mapToLong(StoredObjectInfo::size).sum(), latestLock(objects, now)));
      }
      return (debris);
   }



   /*******************************************************************************
    ** Mark and sweep the content-addressed file store for the plan bucket.
    **
    ** @param alsoDeleting runs about to be deleted: they do not count as retained
    *******************************************************************************/
   private SweepPlan sweepPlan(QRecord plan, Set<Integer> alsoDeleting, BackupStorage deleteSide, Instant now) throws QException
   {
      String        alias       = plan.getValueString("storageBucketAlias");
      BackupStorage readSide    = BackupsRuntime.storageFor(alias);
      Map<String, Integer> counts = new LinkedHashMap<>();

      ///////////////////////////////////////////////////////////////////////////
      // mark: every file the file indexes of retained runs list, counted once   //
      // per run                                                                 //
      ///////////////////////////////////////////////////////////////////////////
      List<QRecord> allRuns = catalog.query(BackupsTables.RUN, new QQueryFilter(new QFilterCriteria("storageBucketAlias", QCriteriaOperator.EQUALS, alias)));
      Map<Integer, QRecord> byId = new LinkedHashMap<>();
      for(QRecord run : allRuns)
      {
         byId.put(run.getValueInteger("id"), run);
      }
      String unreadable = null;
      for(QRecord run : allRuns)
      {
         if(run.getValue("prunedDate") != null || !isSuccessful(run) || alsoDeleting.contains(run.getValueInteger("id")))
         {
            continue;
         }
         try
         {
            for(String sha : filesIndexed(run, readSide))
            {
               counts.merge(sha, 1, Integer::sum);
            }
         }
         catch(Exception e)
         {
            unreadable = "the file index of run " + run.getValueString("runKey") + " could not be read (" + e.getMessage() + ")";
            break;
         }
      }

      List<QRecord> fileObjects = catalog.query(BackupsTables.FILE_OBJECT, new QQueryFilter(new QFilterCriteria("storageBucketAlias", QCriteriaOperator.EQUALS, alias)));
      Map<Integer, Integer> newCounts = new LinkedHashMap<>();
      for(QRecord file : fileObjects)
      {
         if(file.getValue("deletedDate") == null)
         {
            Integer count = counts.getOrDefault(file.getValueString("sha256"), 0);
            if(!count.equals(file.getValueInteger("referenceCount")))
            {
               newCounts.put(file.getValueInteger("id"), count);
            }
         }
      }

      String deferred = unreadable;
      if(deferred == null)
      {
         boolean running = catalog.liveRuns(now.minus(BackupRunCoordinator.HEARTBEAT_STALE)).stream().anyMatch(r -> alias.equals(r.getValueString("storageBucketAlias")))
            || !catalog.queuedRuns().isEmpty();
         if(running)
         {
            deferred = "a backup is running or queued, and its files are not in any file index yet";
         }
      }
      if(unreadable != null)
      {
         newCounts.clear();
      }
      if(deferred != null)
      {
         return (new SweepPlan(deferred, newCounts, List.of()));
      }

      /////////////////////////////////////////////////////////////////////////////////////////////
      // sweep: live objects nothing retained references; not those a recently failed (and so     //
      // perhaps resumable) run registered, and not locked ones, which are reported               //
      /////////////////////////////////////////////////////////////////////////////////////////////
      List<SweepCandidate> candidates = new ArrayList<>();
      for(QRecord file : fileObjects)
      {
         if(file.getValue("deletedDate") != null || counts.containsKey(file.getValueString("sha256")))
         {
            continue;
         }
         QRecord registeredBy = byId.get(file.getValueInteger("lastReferencedRunId"));
         if(registeredBy != null && registeredBy.getValue("prunedDate") == null && !isSuccessful(registeredBy) && startOf(registeredBy).isAfter(now.minus(Duration.ofDays(PruneCalculator.DEBRIS_DAYS))))
         {
            continue;
         }
         Instant lockedUntil = file.getValueInstant("lockedUntil");
         if(lockedUntil == null || !lockedUntil.isAfter(now))
         {
            lockedUntil = deleteSide.head(file.getValueString("objectKey")).map(StoredObjectInfo::lockedUntil).filter(until -> until != null && until.isAfter(now)).orElse(null);
         }
         candidates.add(new SweepCandidate(file.getValueInteger("id"), file.getValueString("sha256"), file.getValueString("objectKey"),
            file.getValueLong("sizeBytes") == null ? 0 : file.getValueLong("sizeBytes"), lockedUntil));
      }
      return (new SweepPlan(null, newCounts, candidates));
   }



   /*******************************************************************************
    ** The hashes a run file index lists as stored (copied or deduplicated).
    *******************************************************************************/
   private Set<String> filesIndexed(QRecord run, BackupStorage readSide) throws Exception
   {
      Set<String> hashes = new LinkedHashSet<>();
      for(QRecord artifact : catalog.query(BackupsTables.ARTIFACT, new QQueryFilter(
         new QFilterCriteria("backupRunId", QCriteriaOperator.EQUALS, run.getValueInteger("id")),
         new QFilterCriteria("artifactType", QCriteriaOperator.EQUALS, "FILE_OBJECTS_INDEX"))))
      {
         String key = run.getValueString("runPrefix") + artifact.getValueString("objectKey");
         try(InputStream raw = readSide.openRead(key);
             InputStream in = "GZIP".equals(artifact.getValueString("compression")) ? new GZIPInputStream(raw) : raw;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
         {
            String line;
            while((line = reader.readLine()) != null)
            {
               if(line.isBlank())
               {
                  continue;
               }
               JsonNode node   = MAPPER.readTree(line);
               String   status = node.path("status").asText("");
               String   sha    = node.path("sha256").asText(null);
               if(sha != null && ("COPIED".equals(status) || "DEDUPLICATED".equals(status)))
               {
                  hashes.add(sha);
               }
            }
         }
      }
      return (hashes);
   }



   /*******************************************************************************
    ** Runs a restore used in the last 30 days, as its source or its safety backup.
    *******************************************************************************/
   private Set<Integer> restoreProtected(Instant now) throws QException
   {
      Instant      cutoff = now.minus(Duration.ofDays(PruneCalculator.RESTORE_PROTECTION_DAYS));
      Set<Integer> ids    = new HashSet<>();
      for(QRecord restore : catalog.query(BackupsTables.RESTORE_RUN, new QQueryFilter()))
      {
         Instant when = restore.getValueInstant("startDate") != null ? restore.getValueInstant("startDate") : restore.getValueInstant("createDate");
         if(when == null || when.isAfter(cutoff))
         {
            if(restore.getValueInteger("sourceBackupRunId") != null)
            {
               ids.add(restore.getValueInteger("sourceBackupRunId"));
            }
            if(restore.getValueInteger("preRestoreBackupRunId") != null)
            {
               ids.add(restore.getValueInteger("preRestoreBackupRunId"));
            }
         }
      }
      return (ids);
   }



   /*******************************************************************************
    ** One summary audit per prune.  Auditing must never make a prune fail.
    *******************************************************************************/
   private void audit(QRecord plan, String prunedBy, int runs, int debris, int files, long bytes)
   {
      try
      {
         AuditAction.execute(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of(), "Prune by " + prunedBy + ": " + runs + " runs, " + debris
            + " debris prefixes and " + files + " stored files deleted (" + bytes + " bytes)");
      }
      catch(Exception e)
      {
         LOG.debug("Could not audit a prune", e, logPair("plan", plan.getValueString("planKey")));
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Instant latestLock(List<StoredObjectInfo> objects, Instant now)
   {
      return (objects.stream().map(StoredObjectInfo::lockedUntil).filter(until -> until != null && until.isAfter(now)).max(Comparator.naturalOrder()).orElse(null));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean isSuccessful(QRecord run)
   {
      return (("SUCCEEDED".equals(run.getValueString("status")) || "SUCCEEDED_WITH_WARNINGS".equals(run.getValueString("status"))) && run.getValueString("manifestKey") != null);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean isVerified(QRecord run)
   {
      return (List.of("STORAGE_OK", "CONTENT_OK", "TEST_RESTORE_OK").contains(run.getValueString("verificationStatus")));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean isLive(QRecord run, Instant now)
   {
      Instant heartbeat = run.getValueInstant("heartbeatDate");
      return (heartbeat != null && heartbeat.isAfter(now.minus(BackupRunCoordinator.HEARTBEAT_STALE)));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Instant startOf(QRecord run)
   {
      return (run.getValueInstant("startDate") != null ? run.getValueInstant("startDate") : run.getValueInstant("createDate"));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static int intOf(QRecord plan, String field, int defaultValue)
   {
      return (plan.getValueInteger(field) == null ? defaultValue : plan.getValueInteger(field));
   }



   /*******************************************************************************
    ** The alias Prune deletes through: the prune alias, else the backup alias.
    *******************************************************************************/
   public static String pruneAlias(QRecord plan)
   {
      String alias = plan.getValueString("pruneBucketAlias");
      return (alias == null || alias.isBlank() ? plan.getValueString("storageBucketAlias") : alias);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static ZoneId zoneOf(QRecord plan)
   {
      try
      {
         String id = plan.getValueString("cronTimeZoneId");
         return (id == null || id.isBlank() ? ZoneId.of("UTC") : ZoneId.of(id));
      }
      catch(Exception e)
      {
         return (ZoneId.of("UTC"));
      }
   }



   /*******************************************************************************
    ** The prefix every run of the plan is under, ending in a slash.
    *******************************************************************************/
   public static String planPrefix(QRecord plan)
   {
      BackupsQBitConfig config   = BackupsRuntime.getConfig();
      String            instance = config != null && config.getInstanceName() != null ? config.getInstanceName() : "qqq";
      String            prefix   = plan.getValueString("prefix") == null || plan.getValueString("prefix").isBlank() ? "qqq-backups" : plan.getValueString("prefix");
      String            trimmed  = prefix;
      while(trimmed.startsWith("/"))
      {
         trimmed = trimmed.substring(1);
      }
      while(trimmed.endsWith("/"))
      {
         trimmed = trimmed.substring(0, trimmed.length() - 1);
      }
      return (trimmed + "/" + instance + "/" + plan.getValueString("planKey") + "/");
   }
}
