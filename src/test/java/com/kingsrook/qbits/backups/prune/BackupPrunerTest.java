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


import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.LifecycleTestSupport;
import com.kingsrook.qbits.backups.LifecycleTestSupport.MutableClock;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Prune end to end on the memory backend with a synthetic clock: real runs are
 ** written day by day, then pruned.  Covers the GFS result on real objects, the
 ** file-store mark-and-sweep, object lock (kept: locked until), debris, the
 ** restore and verification guards, the prune alias, and Review writing nothing.
 *******************************************************************************/
class BackupPrunerTest
{
   private static final Instant DAY_1 = Instant.parse("2026-10-01T02:00:00Z");

   private MutableClock clock;
   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(7);
      clock = LifecycleTestSupport.installClock(DAY_1);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      LifecycleTestSupport.restoreClock();
   }



   /*******************************************************************************
    ** A plan that keeps `daily` days and nothing else.
    *******************************************************************************/
   private QRecord plan(int daily, Map<String, Serializable> more) throws Exception
   {
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("retainDaily", daily, "retainWeekly", 0, "retainMonthly", 0, "retainYearly", 0, "retainMinimum", 0));
      values.putAll(more);
      return (LifecycleTestSupport.plan(values));
   }



   /*******************************************************************************
    ** Run the plan on a given day.
    *******************************************************************************/
   private BackupRunResult runOnDay(QRecord plan, int day) throws Exception
   {
      clock.set(DAY_1.plus(Duration.ofDays(day - 1)));
      BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "SCHEDULED");
      assertThat(result.isUsable()).as("run on day " + day + ": " + result.getErrorMessage()).isTrue();
      return (result);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void deleteAllPosts() throws Exception
   {
      new DeleteAction().execute(new DeleteInput("blogPost").withPrimaryKeys(new ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7))));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private QRecord run(Integer id) throws Exception
   {
      return (catalog.get(BackupsTables.RUN, id).orElseThrow());
   }



   /*******************************************************************************
    ** Four nightly runs, keep two days: the two oldest go, their objects go, their
    ** rows stay as history, and the files only they referenced are swept.  Review
    ** shows the same numbers and writes nothing.
    *******************************************************************************/
   @Test
   void testPruneDeletesOldRunsAndSweepsTheFileStore() throws Exception
   {
      QRecord plan = plan(2, Map.of());
      BackupRunResult r1 = runOnDay(plan, 1);
      BackupRunResult r2 = runOnDay(plan, 2);
      deleteAllPosts();
      BackupRunResult r3 = runOnDay(plan, 3);
      BackupRunResult r4 = runOnDay(plan, 4);
      clock.set(DAY_1.plus(Duration.ofDays(4)));

      List<QRecord> files = catalog.query(BackupsTables.FILE_OBJECT, new QQueryFilter());
      assertThat(files).hasSize(3);

      ////////////
      // review //
      ////////////
      BackupPruner.PrunePlan review = new BackupPruner().plan(plan);
      assertThat(review.toDelete()).extracting(BackupPruner.RunPlan::runId).containsExactlyInAnyOrder(r1.getRunId(), r2.getRunId());
      assertThat(review.keptByClass()).containsEntry(PruneCalculator.RetentionClass.DAILY, 2);
      assertThat(review.lockedNotKept()).isEmpty();
      assertThat(review.sweep().deferredReason()).isNull();
      assertThat(review.sweep().deletable()).hasSize(3);
      assertThat(review.bytesToFree()).isPositive();
      assertThat(run(r1.getRunId()).getValue("prunedDate")).as("review writes nothing").isNull();
      assertThat(BackupsTestUtils.BACKUPS.list(r1.getRunPrefix())).isNotEmpty();
      assertThat(run(r1.getRunId()).getValue("retentionClass")).isNull();

      /////////////
      // execute //
      /////////////
      BackupPruner.Outcome outcome = new BackupPruner().prune(plan, "tester");
      assertThat(outcome.runsDeleted()).isEqualTo(2);
      assertThat(outcome.runsLocked()).isZero();
      assertThat(outcome.filesSwept()).isEqualTo(3);
      assertThat(outcome.bytesFreed()).isEqualTo(review.bytesToFree());

      for(BackupRunResult gone : List.of(r1, r2))
      {
         assertThat(BackupsTestUtils.BACKUPS.list(gone.getRunPrefix())).isEmpty();
         QRecord row = run(gone.getRunId());
         assertThat(row.getValue("prunedDate")).isNotNull();
         assertThat(row.getValueString("prunedByUserId")).isEqualTo("tester");
         assertThat(row.getValueString("retentionClass")).isEqualTo("NONE");
      }
      for(BackupRunResult kept : List.of(r3, r4))
      {
         assertThat(BackupsTestUtils.BACKUPS.getBytes(kept.getManifestKey())).isNotNull();
         assertThat(run(kept.getRunId()).getValue("prunedDate")).isNull();
         assertThat(run(kept.getRunId()).getValueString("retentionClass")).isEqualTo("DAILY");
      }
      for(QRecord file : catalog.query(BackupsTables.FILE_OBJECT, new QQueryFilter()))
      {
         assertThat(file.getValue("deletedDate")).isNotNull();
         assertThat(BackupsTestUtils.BACKUPS.getBytes(file.getValueString("objectKey"))).isNull();
      }

      ///////////////////////////////////////////////////////////////////////////
      // pruning again finds nothing to do (the rows stay, but they are history) //
      ///////////////////////////////////////////////////////////////////////////
      BackupPruner.Outcome again = new BackupPruner().prune(plan, "tester");
      assertThat(again.runsDeleted()).isZero();
      assertThat(again.filesSwept()).isZero();
   }



   /*******************************************************************************
    ** A file a retained run still references is not swept, and its referenceCount is
    ** the number of retained runs that list it.
    *******************************************************************************/
   @Test
   void testFilesOfRetainedRunsAreKeptAndCounted() throws Exception
   {
      QRecord plan = plan(2, Map.of());
      runOnDay(plan, 1);
      runOnDay(plan, 2);
      BackupRunResult r3 = runOnDay(plan, 3);
      clock.set(DAY_1.plus(Duration.ofDays(3)));

      BackupPruner.Outcome outcome = new BackupPruner().prune(plan, "tester");
      assertThat(outcome.runsDeleted()).isEqualTo(1);
      assertThat(outcome.filesSwept()).isZero();
      List<QRecord> files = catalog.query(BackupsTables.FILE_OBJECT, new QQueryFilter());
      assertThat(files).hasSize(3);
      for(QRecord file : files)
      {
         assertThat(file.getValue("deletedDate")).isNull();
         assertThat(file.getValueInteger("referenceCount")).as("referenced by the two retained runs").isEqualTo(2);
         assertThat(BackupsTestUtils.BACKUPS.getBytes(file.getValueString("objectKey"))).isNotNull();
      }
      assertThat(r3.getFilesReferenced()).isEqualTo(4);
   }



   /*******************************************************************************
    ** Object lock is applied to every object at write time; a run whose objects are
    ** still locked is reported "kept: locked until" and not touched; once the lock
    ** has passed, the next prune deletes it.
    *******************************************************************************/
   @Test
   void testLockedRunIsKeptUntilTheLockPasses() throws Exception
   {
      QRecord plan = plan(1, Map.of("objectLockMode", "GOVERNANCE", "objectLockDays", 3));
      BackupRunResult r1 = runOnDay(plan, 1);
      BackupRunResult r2 = runOnDay(plan, 2);

      Instant r1LockedUntil = DAY_1.plus(Duration.ofDays(3));
      List<StoredObjectInfo> r1Objects = BackupsTestUtils.BACKUPS.list(r1.getRunPrefix());
      assertThat(r1Objects).isNotEmpty().allSatisfy(o -> assertThat(o.lockedUntil()).as(o.key()).isEqualTo(r1LockedUntil));
      assertThat(run(r1.getRunId()).getValueInstant("lockedUntil")).isEqualTo(r1LockedUntil);
      for(QRecord file : catalog.query(BackupsTables.FILE_OBJECT, new QQueryFilter()))
      {
         assertThat(BackupsTestUtils.BACKUPS.head(file.getValueString("objectKey")).orElseThrow().lockedUntil()).as("file store objects are locked too").isEqualTo(r1LockedUntil);
      }

      clock.set(DAY_1.plus(Duration.ofDays(2)).plusSeconds(3600));
      BackupPruner.PrunePlan review = new BackupPruner().plan(plan);
      assertThat(review.toDelete()).isEmpty();
      assertThat(review.lockedNotKept()).extracting(BackupPruner.RunPlan::runId).containsExactly(r1.getRunId());
      assertThat(review.lockedNotKept().get(0).lockedUntil()).isEqualTo(r1LockedUntil);

      BackupPruner.Outcome outcome = new BackupPruner().prune(plan, "tester");
      assertThat(outcome.runsDeleted()).isZero();
      assertThat(outcome.runsLocked()).isEqualTo(1);
      assertThat(outcome.messages()).anyMatch(m -> m.contains("kept: locked until " + r1LockedUntil));
      assertThat(BackupsTestUtils.BACKUPS.list(r1.getRunPrefix())).hasSize(r1Objects.size());
      assertThat(run(r1.getRunId()).getValue("prunedDate")).isNull();

      clock.set(DAY_1.plus(Duration.ofDays(4)));
      BackupPruner.Outcome later = new BackupPruner().prune(plan, "tester");
      assertThat(later.runsDeleted()).isEqualTo(1);
      assertThat(BackupsTestUtils.BACKUPS.list(r1.getRunPrefix())).isEmpty();
      assertThat(run(r1.getRunId()).getValue("prunedDate")).isNotNull();
      assertThat(BackupsTestUtils.BACKUPS.getBytes(r2.getManifestKey())).isNotNull();
   }



   /*******************************************************************************
    ** A content-addressed object nobody references but that is still locked is kept
    ** and reported, with its lock date recorded on its row.
    *******************************************************************************/
   @Test
   void testLockedUnreferencedFileObjectIsKept() throws Exception
   {
      QRecord plan = plan(1, Map.of());
      runOnDay(plan, 1);
      deleteAllPosts();
      clock.set(DAY_1.plus(Duration.ofDays(1)));

      String  sha = "ab".repeat(32);
      String  key = "qqq-backups/testapp/_files/sha256/ab/ab/" + sha;
      Instant until = DAY_1.plus(Duration.ofDays(10));
      try(OutputStream out = BackupsTestUtils.BACKUPS.openWrite(key, WriteOptions.create().withObjectLock("GOVERNANCE", until)))
      {
         out.write("old file".getBytes());
      }
      Integer fileId = catalog.insert(BackupsTables.FILE_OBJECT, Map.of("sha256", sha, "sizeBytes", 8L, "storageBucketAlias", "backups", "objectKey", key, "referenceCount", 0));

      BackupPruner.PrunePlan review = new BackupPruner().plan(plan);
      assertThat(review.sweep().locked()).hasSize(1).allSatisfy(c -> assertThat(c.lockedUntil()).isEqualTo(until));

      BackupPruner.Outcome outcome = new BackupPruner().prune(plan, "tester");
      assertThat(outcome.filesLocked()).isEqualTo(1);
      assertThat(outcome.messages()).anyMatch(m -> m.contains("kept: locked until " + until));
      assertThat(BackupsTestUtils.BACKUPS.getBytes(key)).isNotNull();
      QRecord row = catalog.get(BackupsTables.FILE_OBJECT, fileId).orElseThrow();
      assertThat(row.getValue("deletedDate")).isNull();
      assertThat(row.getValueInstant("lockedUntil")).isEqualTo(until);

      clock.set(until.plusSeconds(1));
      assertThat(new BackupPruner().prune(plan, "tester").filesSwept()).isEqualTo(1);
      assertThat(BackupsTestUtils.BACKUPS.getBytes(key)).isNull();
   }



   /*******************************************************************************
    ** The prefix of a run that never got a manifest is debris once its newest object
    ** is older than 7 days, and not before.
    *******************************************************************************/
   @Test
   void testDebrisOlderThanSevenDaysIsAlwaysDeleted() throws Exception
   {
      QRecord plan = plan(7, Map.of());
      BackupsTestUtils.BACKUPS.withFailWritesFor(key -> key.contains("/tables/blogPost/"));
      BackupRunResult failed = new BackupRunCoordinator().runPlan(plan, "SCHEDULED");
      BackupsTestUtils.BACKUPS.withFailWritesFor(null);
      assertThat(failed.getStatus()).isEqualTo("FAILED");
      assertThat(BackupsTestUtils.BACKUPS.list(failed.getRunPrefix())).isNotEmpty();

      BackupRunResult good = runOnDay(plan, 6);
      clock.set(DAY_1.plus(Duration.ofDays(6)));
      assertThat(new BackupPruner().plan(plan).debris()).as("6 days old is not debris yet").isEmpty();

      clock.set(DAY_1.plus(Duration.ofDays(8)));
      BackupPruner.PrunePlan review = new BackupPruner().plan(plan);
      assertThat(review.debris()).hasSize(1).allSatisfy(d -> assertThat(d.prefix()).isEqualTo(failed.getRunPrefix()));

      BackupPruner.Outcome outcome = new BackupPruner().prune(plan, "tester");
      assertThat(outcome.debrisPrefixesDeleted()).isEqualTo(1);
      assertThat(BackupsTestUtils.BACKUPS.list(failed.getRunPrefix())).isEmpty();
      assertThat(run(failed.getRunId()).getValue("prunedDate")).isNotNull();
      assertThat(BackupsTestUtils.BACKUPS.getBytes(good.getManifestKey())).as("a good run is never debris").isNotNull();
   }



   /*******************************************************************************
    ** A run a restore used in the last 30 days is never deleted; one an older restore
    ** used is.  The newest run that passed verification is always kept.
    *******************************************************************************/
   @Test
   void testRestoreAndVerificationGuards() throws Exception
   {
      QRecord plan = plan(1, Map.of());
      BackupRunResult r1 = runOnDay(plan, 1);
      BackupRunResult r2 = runOnDay(plan, 2);
      BackupRunResult r3 = runOnDay(plan, 3);
      BackupRunResult r4 = runOnDay(plan, 4);
      clock.set(DAY_1.plus(Duration.ofDays(40)));

      catalog.insert(BackupsTables.RESTORE_RUN, Map.of("sourceBackupRunId", r1.getRunId(), "status", "SUCCEEDED", "startDate", clock.instant().minus(Duration.ofDays(29))));
      catalog.insert(BackupsTables.RESTORE_RUN, Map.of("sourceBackupRunId", r2.getRunId(), "status", "SUCCEEDED", "startDate", clock.instant().minus(Duration.ofDays(31))));
      catalog.update(BackupsTables.RUN, r3.getRunId(), Map.of("verificationStatus", "CONTENT_OK"));

      BackupPruner.PrunePlan review = new BackupPruner().plan(plan);
      assertThat(review.toDelete()).extracting(BackupPruner.RunPlan::runId).containsExactly(r2.getRunId());
      assertThat(review.keptByGuard()).isEqualTo(2);

      new BackupPruner().prune(plan, "tester");
      assertThat(BackupsTestUtils.BACKUPS.list(r1.getRunPrefix())).as("used by a restore 29 days ago").isNotEmpty();
      assertThat(BackupsTestUtils.BACKUPS.list(r2.getRunPrefix())).as("used by a restore 31 days ago").isEmpty();
      assertThat(BackupsTestUtils.BACKUPS.list(r3.getRunPrefix())).as("newest verified").isNotEmpty();
      assertThat(BackupsTestUtils.BACKUPS.list(r4.getRunPrefix())).isNotEmpty();
   }



   /*******************************************************************************
    ** Nothing in the file store is swept while a backup is running: its files are not
    ** in any index yet.
    *******************************************************************************/
   @Test
   void testSweepIsDeferredWhileARunIsLive() throws Exception
   {
      QRecord plan = plan(1, Map.of());
      runOnDay(plan, 1);
      deleteAllPosts();
      runOnDay(plan, 2);
      clock.set(DAY_1.plus(Duration.ofDays(2)));

      Integer otherPlan = catalog.insert(BackupsTables.PLAN, Map.of("name", "Other", "planKey", "other", "isActive", false, "scopeType", "INSTANCE", "storageBucketAlias", "backups"));
      catalog.insert(BackupsTables.RUN, Map.of("backupPlanId", otherPlan, "runKey", "live-one", "status", "RUNNING", "heartbeatDate", clock.instant().minusSeconds(30),
         "storageBucketAlias", "backups", "startDate", clock.instant().minusSeconds(60)));

      BackupPruner.Outcome outcome = new BackupPruner().prune(plan, "tester");
      assertThat(outcome.runsDeleted()).isEqualTo(1);
      assertThat(outcome.filesSwept()).isZero();
      assertThat(outcome.messages()).anyMatch(m -> m.contains("sweep skipped") && m.contains("running"));
      assertThat(catalog.query(BackupsTables.FILE_OBJECT, new QQueryFilter())).allSatisfy(f -> assertThat(f.getValue("deletedDate")).isNull());
   }



   /*******************************************************************************
    ** Deletes go through the prune alias, not the backup alias.
    *******************************************************************************/
   @Test
   void testDeletesGoThroughThePruneAlias() throws Exception
   {
      CountingStorage pruneSide = new CountingStorage(BackupsTestUtils.BACKUPS);
      CountingStorage writeSide = new CountingStorage(BackupsTestUtils.BACKUPS);
      BackupsRuntime.getConfig().withStorageResolver(alias -> switch(alias)
      {
         case "backups" -> writeSide;
         case "backupsPrune" -> pruneSide;
         case "media" -> BackupsTestUtils.MEDIA;
         default -> null;
      });

      QRecord plan = plan(1, Map.of("pruneBucketAlias", "backupsPrune"));
      runOnDay(plan, 1);
      runOnDay(plan, 2);
      clock.set(DAY_1.plus(Duration.ofDays(2)));
      BackupPruner.Outcome outcome = new BackupPruner().prune(plan, "tester");
      assertThat(outcome.runsDeleted()).isEqualTo(1);
      assertThat(pruneSide.deletes).isPositive();
      assertThat(writeSide.deletes).isZero();
   }



   /*******************************************************************************
    ** A storage that delegates, and counts permanent deletes.
    *******************************************************************************/
   static class CountingStorage implements BackupStorage
   {
      private final BackupStorage delegate;
      int deletes = 0;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      CountingStorage(BackupStorage delegate)
      {
         this.delegate = delegate;
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public OutputStream openWrite(String key, WriteOptions options)
      {
         return (delegate.openWrite(key, options));
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public InputStream openRead(String key)
      {
         return (delegate.openRead(key));
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public Optional<StoredObjectInfo> head(String key)
      {
         return (delegate.head(key));
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public List<StoredObjectInfo> list(String prefix)
      {
         return (delegate.list(prefix));
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void copy(String sourceKey, String destinationKey)
      {
         delegate.copy(sourceKey, destinationKey);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public DeleteResult deletePermanent(String key)
      {
         deletes++;
         return (delegate.deletePermanent(key));
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public String presignGet(String key, Duration ttl, String downloadName)
      {
         return (delegate.presignGet(key, ttl, downloadName));
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public Set<StorageCapability> capabilities()
      {
         return (delegate.capabilities());
      }
   }
}
