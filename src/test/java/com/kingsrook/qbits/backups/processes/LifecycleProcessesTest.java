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

package com.kingsrook.qbits.backups.processes;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.LifecycleTestSupport;
import com.kingsrook.qbits.backups.LifecycleTestSupport.EtlSummaries;
import com.kingsrook.qbits.backups.LifecycleTestSupport.MutableClock;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Back up these records, Test destination and Prune per retention, driven the way
 ** the UI drives them: through the streamed ETL process, Review first, then Result.
 *******************************************************************************/
class LifecycleProcessesTest
{
   private static final Instant DAY_1 = Instant.parse("2026-10-01T02:00:00Z");

   private final BackupCatalog catalog = new BackupCatalog();
   private MutableClock clock;



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
    **
    *******************************************************************************/
   private static String gunzip(byte[] bytes) throws IOException
   {
      return (new String(new GZIPInputStream(new ByteArrayInputStream(bytes)).readAllBytes(), StandardCharsets.UTF_8));
   }



   /*******************************************************************************
    ** Every table that can be backed up gets the action; the qbit own tables, secrets
    ** and sessions do not.
    *******************************************************************************/
   @Test
   void testExportActionIsOnEveryBackupTable()
   {
      assertThat(QContext.getQInstance().getProcess("blogPost.backupExportTable")).isNotNull();
      assertThat(QContext.getQInstance().getProcess("author.backupExportTable")).isNotNull();
      assertThat(QContext.getQInstance().getProcess("backups_run.backupExportTable")).isNull();
      assertThat(QContext.getQInstance().getProcess("userSession.backupExportTable")).isNull();
      assertThat(BackupsRuntime.processPermissions()).containsEntry("blogPost.backupExportTable", "backups.run");
   }



   /*******************************************************************************
    ** Back up these records: an ad-hoc run (no plan) of just the chosen records, with
    ** their files copied, restorable like any other, and by a user without
    ** backups.admin the sensitive field is left out.
    *******************************************************************************/
   @Test
   void testExportSelectedRecordsLeavesSensitiveFieldsOutForNonAdmins() throws Exception
   {
      LifecycleTestSupport.plan(Map.of());
      BackupsRuntime.getConfig().withOpenWhenAnonymous(false);
      QContext.setQSession(new QSession().withPermission("backups.run"));

      EtlSummaries summaries = LifecycleTestSupport.runEtl("blogPost.backupExportTable", "id", List.of(1, 2, 3));
      assertThat(EtlSummaries.texts(summaries.review(), false)).contains("3 records will be backed up")
         .anyMatch(t -> t.startsWith("1 field is sensitive and will be left out"));
      assertThat(EtlSummaries.texts(summaries.result(), true)).contains("1 export finished with warnings", "3 records were written", "2 referenced files were copied or already stored");

      QRecord run = catalog.query(BackupsTables.RUN, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()).stream()
         .filter(r -> r.getValue("backupPlanId") == null).findFirst().orElseThrow();
      assertThat(run.getValueString("status")).isIn("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS");
      assertThat(run.getValueString("securityScope")).isEqualTo("USER");
      assertThat(run.getValueString("runPrefix")).contains("/adhoc/");
      assertThat(run.getValueLong("rowsTotal")).isEqualTo(3L);
      assertThat(run.getValueInteger("filesReferenced")).as("posts 1 and 3 have a file").isEqualTo(2);

      BackupManifest manifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(run.getValueString("manifestKey")));
      assertThat(manifest.tables()).extracting(BackupManifest.TableEntry::name).containsExactly("blogPost");
      assertThat(manifest.table("blogPost").excludedFields()).extracting(BackupManifest.ExcludedField::name).containsExactly("secretNote");
      String text = gunzip(BackupsTestUtils.BACKUPS.getBytes(run.getValueString("runPrefix") + manifest.table("blogPost").parts().get(0).key()));
      assertThat(text.split("\n")).hasSize(3);
      assertThat(text).doesNotContain("do-not-leak");
   }



   /*******************************************************************************
    ** With backups.admin the sensitive field is included, and Review says so.
    *******************************************************************************/
   @Test
   void testExportIncludesSensitiveFieldsForAdmins() throws Exception
   {
      LifecycleTestSupport.plan(Map.of());
      EtlSummaries summaries = LifecycleTestSupport.runEtl("blogPost.backupExportTable", "id", List.of(2));
      assertThat(EtlSummaries.texts(summaries.review(), false)).anyMatch(t -> t.startsWith("1 field is sensitive and will be written in the clear"));
      QRecord run = catalog.query(BackupsTables.RUN, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter()).stream()
         .filter(r -> r.getValue("backupPlanId") == null).findFirst().orElseThrow();
      BackupManifest manifest = BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(run.getValueString("manifestKey")));
      assertThat(gunzip(BackupsTestUtils.BACKUPS.getBytes(run.getValueString("runPrefix") + manifest.table("blogPost").parts().get(0).key()))).contains("do-not-leak-2");
   }



   /*******************************************************************************
    ** With no active plan there is no bucket to write to: Review says so, and nothing runs.
    *******************************************************************************/
   @Test
   void testExportNeedsADestination() throws Exception
   {
      EtlSummaries summaries = LifecycleTestSupport.runEtl("author.backupExportTable", "id", List.of(1));
      assertThat(EtlSummaries.texts(summaries.review(), false)).anyMatch(t -> t.contains("export has no destination"));
      assertThat(EtlSummaries.texts(summaries.result(), true)).anyMatch(t -> t.contains("export failed"));
      assertThat(BackupsTestUtils.BACKUPS.list("")).isEmpty();
   }



   /*******************************************************************************
    ** Test destination: write, read, list and delete a probe, leaving nothing behind.
    *******************************************************************************/
   @Test
   void testDestinationProbeWritesReadsListsAndDeletes() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of());
      EtlSummaries summaries = LifecycleTestSupport.runEtl(TestDestinationProcess.NAME, "id", List.of(plan.getValueInteger("id")));

      List<String> result = EtlSummaries.texts(summaries.result(), true);
      assertThat(result).anyMatch(t -> t.contains("Write a probe object succeeded"))
         .anyMatch(t -> t.contains("Read it back succeeded"))
         .anyMatch(t -> t.contains("List it succeeded"))
         .anyMatch(t -> t.contains("Delete it succeeded"))
         .anyMatch(t -> t.contains("Capabilities:") && t.contains("OBJECT_LOCK: supported"))
         .anyMatch(t -> t.contains("No prune bucket is set"));
      assertThat(BackupsTestUtils.BACKUPS.list("")).as("the probe is deleted again").isEmpty();
   }



   /*******************************************************************************
    ** A failing destination shows as an error line, not a thrown exception.
    *******************************************************************************/
   @Test
   void testDestinationProbeReportsFailures() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of());
      BackupsTestUtils.BACKUPS.withFailWritesFor(key -> true);
      EtlSummaries summaries = LifecycleTestSupport.runEtl(TestDestinationProcess.NAME, "id", List.of(plan.getValueInteger("id")));
      assertThat(summaries.result().stream().filter(l -> l.getStatus() == com.kingsrook.qqq.backend.core.model.actions.processes.Status.ERROR)).hasSize(1);
      assertThat(EtlSummaries.texts(summaries.result(), true)).anyMatch(t -> t.contains("Write a probe object failed") && t.contains("injected write failure"));
   }



   /*******************************************************************************
    ** On a locked bucket nothing is written (a probe object could not be deleted);
    ** the prefix is listed and the newest manifest is looked at with a head.
    *******************************************************************************/
   @Test
   void testDestinationProbeIsHeadOnlyOnALockedBucket() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of("objectLockMode", "GOVERNANCE", "objectLockDays", 3));
      BackupRunResult run = new BackupRunCoordinator().runPlan(plan, "MANUAL");
      assertThat(run.isUsable()).isTrue();
      int objectsBefore = BackupsTestUtils.BACKUPS.list("").size();

      EtlSummaries summaries = LifecycleTestSupport.runEtl(TestDestinationProcess.NAME, "id", List.of(plan.getValueInteger("id")));
      assertThat(EtlSummaries.texts(summaries.review(), false)).anyMatch(t -> t.contains("destination is object locked: the test will only read"));
      assertThat(EtlSummaries.texts(summaries.result(), true)).anyMatch(t -> t.contains("Write and delete a probe object succeeded: skipped"))
         .anyMatch(t -> t.contains("List the plan prefix succeeded") && t.contains("objects under"))
         .anyMatch(t -> t.contains("Head the newest manifest succeeded: found "));
      assertThat(BackupsTestUtils.BACKUPS.list("")).hasSize(objectsBefore);
   }



   /*******************************************************************************
    ** Prune through the UI path: Review shows the runs to delete, the runs kept by
    ** class, the files to sweep and the space freed; Result does it.
    *******************************************************************************/
   @Test
   void testPruneReviewAndResult() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of("retainDaily", 2, "retainWeekly", 0, "retainMonthly", 0, "retainYearly", 0, "retainMinimum", 0));
      BackupRunResult r1 = null;
      for(int day = 1; day <= 4; day++)
      {
         clock.set(DAY_1.plus(Duration.ofDays(day - 1)));
         if(day == 3)
         {
            new com.kingsrook.qqq.backend.core.actions.tables.DeleteAction().execute(new com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput("blogPost")
               .withPrimaryKeys(new java.util.ArrayList<>(List.of(1, 2, 3, 4, 5, 6, 7))));
         }
         BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "SCHEDULED");
         r1 = r1 == null ? result : r1;
      }

      EtlSummaries summaries = LifecycleTestSupport.runEtl(PruneProcess.NAME, "id", List.of(plan.getValueInteger("id")));
      assertThat(EtlSummaries.texts(summaries.review(), false)).contains("1 plan will be pruned", "2 runs will be deleted", "2 runs are kept as daily backups",
         "3 stored files that no retained backup references will be deleted");
      assertThat(EtlSummaries.texts(summaries.review(), false)).anyMatch(t -> t.endsWith("MiB of storage will be freed"));
      assertThat(EtlSummaries.texts(summaries.result(), true)).contains("1 plan was pruned", "2 runs were deleted", "3 stored files were deleted");
      assertThat(BackupsTestUtils.BACKUPS.list(r1.getRunPrefix())).isEmpty();
   }



   /*******************************************************************************
    ** The run table action prunes the plans of the selected runs, once each.
    *******************************************************************************/
   @Test
   void testPruneRunsActionPrunesTheirPlans() throws Exception
   {
      QRecord plan = LifecycleTestSupport.plan(Map.of("retainDaily", 1, "retainWeekly", 0, "retainMonthly", 0, "retainYearly", 0, "retainMinimum", 0));
      BackupRunResult first  = null;
      BackupRunResult second = null;
      for(int day = 1; day <= 3; day++)
      {
         clock.set(DAY_1.plus(Duration.ofDays(day - 1)));
         BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "SCHEDULED");
         first = first == null ? result : first;
         second = day == 2 ? result : second;
      }
      EtlSummaries summaries = LifecycleTestSupport.runEtl(PruneProcess.NAME_RUNS, "id", List.of(first.getRunId(), second.getRunId()));
      assertThat(EtlSummaries.texts(summaries.review(), false)).contains("1 plan will be pruned", "2 runs will be deleted");
      assertThat(EtlSummaries.texts(summaries.result(), true)).contains("2 runs were deleted");
   }
}
