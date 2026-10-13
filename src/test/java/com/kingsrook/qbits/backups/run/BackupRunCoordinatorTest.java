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


import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.LifecycleTestSupport;
import com.kingsrook.qbits.backups.LifecycleTestSupport.EtlSummaries;
import com.kingsrook.qbits.backups.LifecycleTestSupport.MutableClock;
import com.kingsrook.qbits.backups.LifecycleTestSupport.RecordingAlertSink;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.processes.CancelRunProcess;
import com.kingsrook.qbits.backups.processes.ResumeRunProcess;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The run lease (one run per plan, an instance limit with queueing), cancel, and
 ** resume after an injected failure.
 *******************************************************************************/
class BackupRunCoordinatorTest
{
   private static final Instant START = Instant.parse("2026-10-12T02:00:00Z");

   private MutableClock       clock;
   private RecordingAlertSink alerts;
   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(7);
      clock = LifecycleTestSupport.installClock(START);
      alerts = new RecordingAlertSink();
      BackupsRuntime.setAlertSink(alerts);
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
    ** A run row that says another run of the plan is in flight.
    *******************************************************************************/
   private Integer liveRun(Integer planId, String status, Instant heartbeat) throws Exception
   {
      Map<String, Serializable> values = new java.util.LinkedHashMap<>();
      values.put("backupPlanId", planId);
      values.put("runKey", "live-" + System.nanoTime());
      values.put("status", status);
      values.put("startDate", heartbeat);
      values.put("heartbeatDate", heartbeat);
      values.put("storageBucketAlias", "backups");
      return (catalog.insert(BackupsTables.RUN, values));
   }



   /*******************************************************************************
    ** A trigger that finds the plan already running is skipped, visibly: a run row
    ** that says so, and a warning.  Nothing is written to storage.
    *******************************************************************************/
   @Test
   void testSecondTriggerIsSkippedNotSilent() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of());
      Integer live = liveRun(plan.getValueInteger("id"), "RUNNING", clock.instant().minusSeconds(120));

      BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "SCHEDULED");
      assertThat(result.getStatus()).isEqualTo(BackupRunCoordinator.SKIPPED_CONCURRENT);
      assertThat(result.getErrorMessage()).contains("SKIPPED_CONCURRENT").contains("already running");

      QRecord row = catalog.get(BackupsTables.RUN, result.getRunId()).orElseThrow();
      assertThat(row.getValueString("status")).isEqualTo("CANCELLED");
      assertThat(row.getValueString("errorMessage")).startsWith("SKIPPED_CONCURRENT");
      assertThat(row.getValueInteger("warningCount")).isEqualTo(1);
      assertThat(alerts.raised).containsExactly("backups.run.skipped_concurrent|backups.run.skipped_concurrent:nightly");
      assertThat(BackupsTestUtils.BACKUPS.list("")).isEmpty();
      assertThat(catalog.get(BackupsTables.RUN, live).orElseThrow().getValueString("status")).as("the live run is left alone").isEqualTo("RUNNING");
   }



   /*******************************************************************************
    ** A run that stopped sending heartbeats (a killed pod) does not hold the lease.
    *******************************************************************************/
   @Test
   void testStaleHeartbeatDoesNotBlock() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of());
      liveRun(plan.getValueInteger("id"), "RUNNING", clock.instant().minus(Duration.ofMinutes(11)));

      BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "SCHEDULED");
      assertThat(result.isUsable()).as(result.getErrorMessage()).isTrue();
      QRecord row = catalog.get(BackupsTables.RUN, result.getRunId()).orElseThrow();
      assertThat(row.getValueString("trigger")).isEqualTo("SCHEDULED");
      assertThat(row.getValueString("requestedByUserId")).isEqualTo("system:scheduler");
      assertThat(row.getValueString("securityScope")).isEqualTo("FULL");
   }



   /*******************************************************************************
    ** At the instance limit a run waits, QUEUED; when the live run is gone it starts,
    ** oldest first, and the plan lease still applies to queued runs.
    *******************************************************************************/
   @Test
   void testInstanceLimitQueuesAndDrains() throws Exception
   {
      QRecord first  = LifecycleTestSupport.plan(Map.of());
      QRecord second = LifecycleTestSupport.plan(Map.of("name", "Second", "planKey", "second"));
      Integer live   = liveRun(first.getValueInteger("id"), "RUNNING", clock.instant().minusSeconds(30));

      BackupRunResult queued = new BackupRunCoordinator().runPlan(second, "MANUAL");
      assertThat(queued.getStatus()).isEqualTo(BackupRunCoordinator.QUEUED);
      assertThat(catalog.get(BackupsTables.RUN, queued.getRunId()).orElseThrow().getValueString("status")).isEqualTo("QUEUED");
      assertThat(BackupsTestUtils.BACKUPS.list("")).isEmpty();
      assertThat(new BackupRunCoordinator().drainQueue()).as("no slot yet").isEmpty();

      catalog.update(BackupsTables.RUN, live, Map.of("status", "SUCCEEDED"));
      List<BackupRunResult> drained = new BackupRunCoordinator().drainQueue();
      assertThat(drained).hasSize(1);
      assertThat(drained.get(0).getRunId()).isEqualTo(queued.getRunId());
      assertThat(drained.get(0).isUsable()).as(drained.get(0).getErrorMessage()).isTrue();
      QRecord row = catalog.get(BackupsTables.RUN, queued.getRunId()).orElseThrow();
      assertThat(row.getValueString("trigger")).isEqualTo("MANUAL");
      assertThat(row.getValueString("runKey")).doesNotStartWith("queued-");
   }



   /*******************************************************************************
    ** A raised limit lets two plans run side by side.
    *******************************************************************************/
   @Test
   void testMaxConcurrentRunsRaised() throws Exception
   {
      BackupsRuntime.getConfig().withMaxConcurrentRuns(2);
      QRecord first  = LifecycleTestSupport.plan(Map.of());
      QRecord second = LifecycleTestSupport.plan(Map.of("name", "Second", "planKey", "second"));
      liveRun(first.getValueInteger("id"), "RUNNING", clock.instant().minusSeconds(30));
      assertThat(new BackupRunCoordinator().runPlan(second, "MANUAL").isUsable()).isTrue();
   }



   /*******************************************************************************
    ** Cancel is cooperative: the run row says CANCELLING, and the run stops at its
    ** next page, ends CANCELLED, leaves no manifest, and raises no failure alert.
    *******************************************************************************/
   @Test
   void testCancelStopsAtTheNextPage() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of("pageSize", 2));
      int[]   seen = { 0 };
      BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "MANUAL", request ->
      {
         TableReader keyset = request.getReader();
         request.withReader((table, filter, startAfter, pageSize, transaction, sink) -> keyset.read(table, filter, startAfter, pageSize, transaction, record ->
         {
            sink.accept(record);
            if("blogPost".equals(table.getName()) && ++seen[0] == 3)
            {
               try
               {
                  QRecord running = catalog.where(BackupsTables.RUN, "backupPlanId", plan.getValueInteger("id")).get(0);
                  catalog.update(BackupsTables.RUN, running.getValueInteger("id"), Map.of("status", "CANCELLING"));
               }
               catch(Exception e)
               {
                  throw (new IllegalStateException(e));
               }
            }
         }));
      });

      assertThat(result.getStatus()).isEqualTo("CANCELLED");
      assertThat(seen[0]).as("stopped at the next page, not at the end").isBetween(3, 5);
      assertThat(result.isUsable()).isFalse();
      assertThat(BackupsTestUtils.BACKUPS.list(result.getRunPrefix() + "manifest.json")).isEmpty();
      QRecord row = catalog.get(BackupsTables.RUN, result.getRunId()).orElseThrow();
      assertThat(row.getValueString("status")).isEqualTo("CANCELLED");
      assertThat(row.getValue("endDate")).isNotNull();
      assertThat(alerts.raised).isEmpty();
   }



   /*******************************************************************************
    ** The Cancel action: a live run is asked to stop, a queued run is cancelled at
    ** once, a run with no heartbeat is closed, and a finished run cannot be cancelled.
    *******************************************************************************/
   @Test
   void testCancelProcess() throws Exception
   {
      QRecord plan    = LifecycleTestSupport.plan(Map.of());
      Integer live    = liveRun(plan.getValueInteger("id"), "RUNNING", clock.instant().minusSeconds(10));
      Integer queued  = liveRun(plan.getValueInteger("id"), "QUEUED", clock.instant());
      Integer stale   = liveRun(plan.getValueInteger("id"), "RUNNING", clock.instant().minus(Duration.ofHours(1)));
      Integer done    = liveRun(plan.getValueInteger("id"), "SUCCEEDED", clock.instant());

      EtlSummaries summaries = LifecycleTestSupport.runEtl(CancelRunProcess.NAME, "id", List.of(live, queued, stale, done));
      assertThat(EtlSummaries.texts(summaries.review(), false)).contains("1 running run will be asked to stop at its next page", "1 queued run will be cancelled")
         .anyMatch(t -> t.startsWith("1 run has lost its heartbeat")).anyMatch(t -> t.startsWith("1 run cannot be cancelled"));
      assertThat(EtlSummaries.texts(summaries.result(), true)).contains("1 running run was asked to stop at its next page", "1 queued run was cancelled");

      assertThat(catalog.get(BackupsTables.RUN, live).orElseThrow().getValueString("status")).isEqualTo("CANCELLING");
      assertThat(catalog.get(BackupsTables.RUN, queued).orElseThrow().getValueString("status")).isEqualTo("CANCELLED");
      assertThat(catalog.get(BackupsTables.RUN, stale).orElseThrow().getValueString("status")).isEqualTo("CANCELLED");
      assertThat(catalog.get(BackupsTables.RUN, done).orElseThrow().getValueString("status")).isEqualTo("SUCCEEDED");
      assertThat(catalog.isCancelRequested(live)).isTrue();
   }



   /*******************************************************************************
    ** The row reader that fails once, after some rows of one table.
    *******************************************************************************/
   private static TableReader failingAfter(TableReader delegate, String tableName, int rows)
   {
      int[] seen = { 0 };
      return ((table, filter, startAfter, pageSize, transaction, sink) -> delegate.read(table, filter, startAfter, pageSize, transaction, record ->
      {
         if(tableName.equals(table.getName()) && ++seen[0] > rows)
         {
            throw (new IllegalStateException("injected failure in " + tableName));
         }
         sink.accept(record);
      }));
   }



   /*******************************************************************************
    ** A run that fails in the middle of a table resumes: the finished table is kept,
    ** the interrupted table carries on after the last key of its last complete part,
    ** and the result is the same backup a clean run makes (same rows hash, same
    ** parts, same file index).
    *******************************************************************************/
   @Test
   void testResumeAfterInjectedFailure() throws Exception
   {
      Map<String, Serializable> perTableTinyParts = Map.of("consistency", "PER_TABLE", "pageSize", 2, "targetPartBytes", 1L);

      /////////////////////////////////////////////////////
      // a clean run, for the numbers a resume must match //
      /////////////////////////////////////////////////////
      QRecord         clean       = LifecycleTestSupport.plan(Map.of("name", "Clean", "planKey", "clean", "consistency", "PER_TABLE", "pageSize", 2, "targetPartBytes", 1L));
      BackupRunResult cleanResult = new BackupRunCoordinator().runPlan(clean, "MANUAL");
      assertThat(cleanResult.isUsable()).as(cleanResult.getErrorMessage()).isTrue();
      BackupManifest cleanManifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(cleanResult.getManifestKey()));

      ///////////////////////////////////////////////////////////////
      // the run that fails on the 5th of the 7 blogPost rows         //
      ///////////////////////////////////////////////////////////////
      QRecord         plan   = LifecycleTestSupport.plan(perTableTinyParts);
      BackupRunResult failed = new BackupRunCoordinator().runPlan(plan, "MANUAL", request -> request.withReader(failingAfter(request.getReader(), "blogPost", 4)));
      assertThat(failed.getStatus()).isEqualTo("FAILED");
      assertThat(failed.getErrorMessage()).contains("injected failure in blogPost");
      assertThat(BackupsTestUtils.BACKUPS.list(failed.getRunPrefix() + "manifest.json")).isEmpty();

      QRecord failedRow = catalog.get(BackupsTables.RUN, failed.getRunId()).orElseThrow();
      assertThat(failedRow.getValueString("consistencyAchieved")).isEqualTo("PER_TABLE");
      ResumeState state = BackupResumePlanner.plan(failedRow);
      assertThat(state.table("author").complete()).isTrue();
      assertThat(state.table("blogPost").complete()).isFalse();
      assertThat(state.table("blogPost").parts()).as("a part per row: 4 rows were written, the 5th failed before it was").hasSize(4);
      assertThat(state.table("blogPost").lastKey()).isEqualTo("4");
      assertThat(state.table("blogPost").rows()).isEqualTo(4);
      byte[] authorPart = BackupsTestUtils.BACKUPS.getBytes(failed.getRunPrefix() + "tables/author/part-00001.jsonl.gz");

      ////////////
      // review //
      ////////////
      EtlSummaries summaries = LifecycleTestSupport.runEtl(ResumeRunProcess.NAME, "id", List.of(failed.getRunId()));
      assertThat(EtlSummaries.texts(summaries.review(), false)).contains("1 run will be resumed", "1 table is complete and will be kept as it is")
         .anyMatch(t -> t.startsWith("1 table will carry on from its last complete part") && t.contains("blogPost after key 4"));
      assertThat(EtlSummaries.texts(summaries.result(), true)).contains("1 run succeeded after being resumed");

      QRecord resumedRow = catalog.get(BackupsTables.RUN, failed.getRunId()).orElseThrow();
      assertThat(resumedRow.getValueString("status")).isEqualTo("SUCCEEDED");
      assertThat(resumedRow.getValueString("runKey")).isEqualTo(failed.getRunKey());
      assertThat(resumedRow.getValueString("errorMessage")).isNull();
      assertThat(resumedRow.getValueInstant("startDate")).as("the original start is kept").isEqualTo(failedRow.getValueInstant("startDate"));
      assertThat(BackupsTestUtils.BACKUPS.getBytes(failed.getRunPrefix() + "tables/author/part-00001.jsonl.gz")).as("a finished table is not rewritten").isEqualTo(authorPart);

      BackupManifest manifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(resumedRow.getValueString("manifestKey")));
      assertThat(manifest.table("blogPost").rowCount()).isEqualTo(7);
      assertThat(manifest.table("blogPost").parts()).hasSize(7);
      assertThat(manifest.table("blogPost").rowsHash()).isEqualTo(cleanManifest.table("blogPost").rowsHash());
      assertThat(manifest.table("author").rowsHash()).isEqualTo(cleanManifest.table("author").rowsHash());
      assertThat(manifest.table("blogPost").parts()).extracting(BackupManifest.Part::sha256Raw).isEqualTo(cleanManifest.table("blogPost").parts().stream().map(BackupManifest.Part::sha256Raw).toList());
      assertThat(manifest.files().objects()).isEqualTo(cleanManifest.files().objects());
      assertThat(BackupsTestUtils.BACKUPS.getBytes(failed.getRunPrefix() + "files/index-00001.jsonl.gz")).isNotNull();

      List<QRecord> artifacts = catalog.where(BackupsTables.ARTIFACT, "backupRunId", failed.getRunId());
      assertThat(artifacts.stream().filter(a -> "blogPost".equals(a.getValueString("tableName")) && a.getValue("rowsHash") != null)).as("only the last part carries the rows hash").hasSize(1);
      assertThat(artifacts.stream().filter(a -> "TABLE_DATA".equals(a.getValueString("artifactType")))).hasSize(3 + 7);
      assertThat(resumedRow.getValueLong("rowsTotal")).isEqualTo(10L);
   }



   /*******************************************************************************
    ** A run that is not failed, or that read from one snapshot, cannot be resumed,
    ** and says why.
    *******************************************************************************/
   @Test
   void testResumeIsRefusedWhenItCannotCarryOn() throws Exception
   {
      QRecord ok = LifecycleTestSupport.plan(Map.of());
      BackupRunResult good = new BackupRunCoordinator().runPlan(ok, "MANUAL");
      BackupRunResult refused = new BackupRunCoordinator().resume(catalog.get(BackupsTables.RUN, good.getRunId()).orElseThrow());
      assertThat(refused.getStatus()).isEqualTo("FAILED");
      assertThat(refused.getErrorMessage()).contains("Only a failed, timed out or abandoned run can be resumed");

      QRecord snapshot = LifecycleTestSupport.plan(Map.of("name", "Snap", "planKey", "snap", "consistency", "SNAPSHOT_REQUIRED"));
      BackupRunResult failed = new BackupRunCoordinator().runPlan(snapshot, "MANUAL");
      assertThat(failed.getStatus()).isEqualTo("FAILED");
      BackupRunResult snapshotRefused = new BackupRunCoordinator().resume(catalog.get(BackupsTables.RUN, failed.getRunId()).orElseThrow());
      assertThat(snapshotRefused.getErrorMessage()).contains("one consistent snapshot").contains("Run the plan again");

      EtlSummaries summaries = LifecycleTestSupport.runEtl(ResumeRunProcess.NAME, "id", List.of(failed.getRunId()));
      assertThat(EtlSummaries.texts(summaries.review(), false)).anyMatch(t -> t.startsWith("1 run cannot be resumed") && t.contains("one consistent snapshot"));
   }



   /*******************************************************************************
    ** A table written as CSV cannot rebuild its rows hash from its parts, so the
    ** interrupted table starts over, and the result is still right.
    *******************************************************************************/
   @Test
   void testResumeRestartsAnInterruptedCsvTable() throws Exception
   {
      QRecord         plan   = LifecycleTestSupport.plan(Map.of("consistency", "PER_TABLE", "pageSize", 2, "targetPartBytes", 1L, "format", "CSV"));
      BackupRunResult failed = new BackupRunCoordinator().runPlan(plan, "MANUAL", request -> request.withReader(failingAfter(request.getReader(), "blogPost", 4)));
      assertThat(failed.getStatus()).isEqualTo("FAILED");

      BackupRunResult resumed = new BackupRunCoordinator().resume(catalog.get(BackupsTables.RUN, failed.getRunId()).orElseThrow());
      assertThat(resumed.isUsable()).as(resumed.getErrorMessage()).isTrue();
      BackupManifest manifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(resumed.getManifestKey()));
      assertThat(manifest.table("blogPost").rowCount()).isEqualTo(7);
      assertThat(manifest.table("blogPost").parts()).hasSize(7);
      assertThat(catalog.where(BackupsTables.ARTIFACT, "backupRunId", failed.getRunId()).stream().filter(a -> "blogPost".equals(a.getValueString("tableName")))).hasSize(7);
   }
}
