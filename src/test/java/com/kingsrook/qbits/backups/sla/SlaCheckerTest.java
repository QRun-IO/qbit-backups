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

package com.kingsrook.qbits.backups.sla;


import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.verify.BackupVerifier;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.kingsrook.qbits.backups.VerifyTestSupport.backup;
import static com.kingsrook.qbits.backups.VerifyTestSupport.clock;
import static com.kingsrook.qbits.backups.VerifyTestSupport.plan;
import static com.kingsrook.qbits.backups.VerifyTestSupport.reload;
import static com.kingsrook.qbits.backups.VerifyTestSupport.sink;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Every SLA state of the checker, driven by a synthetic clock, with the alerts it
 ** raises and resolves recorded through a recording sink.
 *******************************************************************************/
class SlaCheckerTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(7);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private SlaChecker.PlanResult check(QRecord plan) throws Exception
   {
      return (new SlaChecker().check(reload(plan)));
   }



   /*******************************************************************************
    ** A new plan that has not run yet is UNKNOWN, then LATE once slaHours pass; a
    ** manual-only plan with no slaHours never is.
    *******************************************************************************/
   @Test
   void testNewPlanThenLate() throws Exception
   {
      QRecord plan = plan(Map.of("critical", true));
      assertThat(check(plan).status()).isEqualTo(SlaChecker.UNKNOWN);
      assertThat(sink.open).isEmpty();

      clock.advance(Duration.ofHours(27));
      SlaChecker.PlanResult late = check(plan);
      assertThat(late.status()).isEqualTo(SlaChecker.LATE);
      assertThat(late.previousStatus()).isEqualTo(SlaChecker.UNKNOWN);
      assertThat(reload(plan).getValueString("slaStatus")).isEqualTo("LATE");
      BackupAlertsAssert.assertOpen("backups.plan.missed:nightly", BackupAlertSink.Severity.CRITICAL);

   }



   /*******************************************************************************
    ** The plan.missed alert is error for an ordinary plan, critical for a critical
    ** one, and resolves when the next run succeeds.
    *******************************************************************************/
   @Test
   void testLateRaisesAndResolves() throws Exception
   {
      QRecord plan = plan(Map.of());
      backup(plan);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
      assertThat(sink.open).isEmpty();

      clock.advance(Duration.ofHours(27));
      assertThat(check(plan).status()).isEqualTo(SlaChecker.LATE);
      BackupAlertsAssert.assertOpen("backups.plan.missed:nightly", BackupAlertSink.Severity.ERROR);
      assertThat(sink.openAlert("backups.plan.missed:nightly").detail()).containsEntry("plan", "nightly").containsEntry("slaHours", "26");

      backup(plan);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
      assertThat(sink.isOpen("backups.plan.missed:nightly")).isFalse();
      assertThat(sink.heartbeats).contains("backups.plan:nightly");
   }



   /*******************************************************************************
    ** A manual-only plan with no slaHours has no SLA; a scheduled one defaults to 26.
    *******************************************************************************/
   @Test
   void testManualPlanHasNoSlaUnlessScheduled() throws Exception
   {
      QRecord manual = plan(Map.of("name", "Manual", "planKey", "manual"));
      new BackupCatalog().update(BackupsTables.PLAN, manual.getValueInteger("id"), java.util.Collections.singletonMap("slaHours", null));
      clock.advance(Duration.ofDays(3));
      assertThat(check(manual).status()).isEqualTo(SlaChecker.UNKNOWN);

      QRecord scheduled = plan(Map.of("name", "Sched", "planKey", "sched", "cronExpression", "0 0 2 * * ?"));
      new BackupCatalog().update(BackupsTables.PLAN, scheduled.getValueInteger("id"), java.util.Collections.singletonMap("slaHours", null));
      assertThat(check(scheduled).status()).isEqualTo(SlaChecker.LATE);
   }



   /*******************************************************************************
    ** Two failed runs in a row are FAILING (error); one is only a warning; a later
    ** success clears it.
    *******************************************************************************/
   @Test
   void testFailingAfterTwoFailures() throws Exception
   {
      QRecord plan = plan(Map.of());
      BackupsTestUtils.BACKUPS.withFailWritesFor(key -> key.contains("/tables/"));

      assertThat(backup(plan).getStatus()).isEqualTo("FAILED");
      assertThat(check(plan).status()).isEqualTo(SlaChecker.UNKNOWN);
      BackupAlertsAssert.assertOpen("backups.run.failed:nightly", BackupAlertSink.Severity.WARNING);

      assertThat(backup(plan).getStatus()).isEqualTo("FAILED");
      assertThat(check(plan).status()).isEqualTo(SlaChecker.FAILING);
      BackupAlertsAssert.assertOpen("backups.run.failed:nightly", BackupAlertSink.Severity.ERROR);

      BackupsTestUtils.BACKUPS.withFailWritesFor(null);
      assertThat(backup(plan).getStatus()).startsWith("SUCCEEDED");
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
      assertThat(sink.isOpen("backups.run.failed:nightly")).isFalse();
   }



   /*******************************************************************************
    ** A deleted manifest is MISSING_IN_STORAGE with a critical alert (a head, cached
    ** for an hour); putting it back clears it.
    *******************************************************************************/
   @Test
   void testDeletedManifestIsMissingInStorage() throws Exception
   {
      QRecord         plan   = plan(Map.of());
      BackupRunResult result = backup(plan);
      byte[]          bytes  = BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey());

      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
      BackupsTestUtils.BACKUPS.removeIgnoringLock(result.getManifestKey());

      clock.advance(Duration.ofMinutes(10));
      assertThat(check(plan).status()).as("the earlier positive head is trusted for an hour").isEqualTo(SlaChecker.OK);

      clock.advance(Duration.ofMinutes(55));
      assertThat(check(plan).status()).isEqualTo(SlaChecker.MISSING_IN_STORAGE);
      BackupAlertsAssert.assertOpen("backups.run.missing_in_storage:nightly", BackupAlertSink.Severity.CRITICAL);
      assertThat(check(plan).status()).as("a negative answer is never cached").isEqualTo(SlaChecker.MISSING_IN_STORAGE);

      BackupsTestUtils.BACKUPS.putBytes(result.getManifestKey(), bytes);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
      assertThat(sink.isOpen("backups.run.missing_in_storage:nightly")).isFalse();
   }



   /*******************************************************************************
    ** A RUNNING run whose heartbeat is older than ten minutes is marked ABANDONED
    ** and the plan is ABANDONED; a fresh heartbeat is left alone.
    *******************************************************************************/
   @Test
   void testStaleHeartbeatIsAbandoned() throws Exception
   {
      QRecord plan = plan(Map.of());
      backup(plan);
      Integer stale = runRow(plan, "20261012T000000Z-aaaa", "RUNNING", clock.instant());
      clock.advance(Duration.ofMinutes(5));
      Integer fresh = runRow(plan, "20261012T000500Z-bbbb", "RUNNING", clock.instant());

      clock.advance(Duration.ofMinutes(6));
      SlaChecker.PlanResult result = check(plan);
      assertThat(result.abandonedRuns()).isEqualTo(1);
      assertThat(new BackupCatalog().get(BackupsTables.RUN, stale).orElseThrow().getValueString("status")).isEqualTo("ABANDONED");
      assertThat(new BackupCatalog().get(BackupsTables.RUN, stale).orElseThrow().getValueString("errorMessage")).contains("heartbeat");
      assertThat(new BackupCatalog().get(BackupsTables.RUN, fresh).orElseThrow().getValueString("status")).isEqualTo("RUNNING");

      assertThat(result.status()).isEqualTo(SlaChecker.ABANDONED);
      BackupAlertsAssert.assertOpen("backups.run.failed:nightly", BackupAlertSink.Severity.WARNING);

      new BackupCatalog().update(BackupsTables.RUN, fresh, java.util.Collections.singletonMap("status", "SUCCEEDED"));
      backup(plan);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
      assertThat(sink.isOpen("backups.run.failed:nightly")).isFalse();
   }



   /*******************************************************************************
    ** UNVERIFIED when no content verification has passed within verifySlaDays; a
    ** passed verification clears it.
    *******************************************************************************/
   @Test
   void testUnverifiedThenVerified() throws Exception
   {
      QRecord plan = plan(Map.of("slaHours", 24 * 30));
      backup(plan);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);

      clock.advance(Duration.ofDays(9));
      BackupRunResult second = backup(plan);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.UNVERIFIED);
      BackupAlertsAssert.assertOpen("backups.verify.overdue:nightly", BackupAlertSink.Severity.WARNING);

      new BackupVerifier().verifyThrough(second.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");
      assertThat(sink.isOpen("backups.verify.overdue:nightly")).isFalse();
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);

      clock.advance(Duration.ofDays(9));
      backup(plan);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.UNVERIFIED);
   }



   /*******************************************************************************
    ** OWNER_INVALID when the configured validator rejects the plan owner.
    *******************************************************************************/
   @Test
   void testOwnerInvalid() throws Exception
   {
      QRecord plan = plan(Map.of("ownerUserId", "bob"));
      backup(plan);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);

      BackupsRuntime.getConfig().withPlanOwnerValidator(owner -> false);
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OWNER_INVALID);
      BackupAlertsAssert.assertOpen("backups.plan.owner_invalid:nightly", BackupAlertSink.Severity.WARNING);

      BackupsRuntime.getConfig().withPlanOwnerValidator(owner -> "bob".equals(owner));
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
      assertThat(sink.isOpen("backups.plan.owner_invalid:nightly")).isFalse();

      BackupsRuntime.getConfig().withPlanOwnerValidator(owner ->
      {
         throw (new IllegalStateException("directory down"));
      });
      assertThat(check(plan).status()).as("a validator that fails is not a verdict").isEqualTo(SlaChecker.OK);
   }



   /*******************************************************************************
    ** Worst state first: MISSING_IN_STORAGE beats LATE.
    *******************************************************************************/
   @Test
   void testPrecedence() throws Exception
   {
      QRecord         plan   = plan(Map.of());
      BackupRunResult result = backup(plan);
      BackupsTestUtils.BACKUPS.removeIgnoringLock(result.getManifestKey());
      clock.advance(Duration.ofDays(2));
      assertThat(check(plan).status()).isEqualTo(SlaChecker.MISSING_IN_STORAGE);
      assertThat(sink.isOpen("backups.plan.missed:nightly")).as("the alerts are independent of the status shown").isTrue();
      assertThat(sink.isOpen("backups.run.missing_in_storage:nightly")).isTrue();
   }



   /*******************************************************************************
    ** The daily bucket listing reports orphans and debris (no manifest older than 24h)
    ** once a day, raises an info alert, and resolves it when the bucket is clean.
    *******************************************************************************/
   @Test
   void testBucketAuditFindsOrphansAndDebris() throws Exception
   {
      QRecord plan = plan(Map.of());
      backup(plan);

      String debris = "qqq-backups/testapp/nightly/2026/09/01/20260901T020000Z-dead/tables/author/part-00001.jsonl.gz";
      String orphan = "qqq-backups/testapp/nightly/2026/09/02/20260902T020000Z-beef/manifest.json";
      BackupsTestUtils.BACKUPS.withClock(Clock.fixed(clock.instant().minus(Duration.ofDays(3)), java.time.ZoneOffset.UTC));
      BackupsTestUtils.BACKUPS.putBytes(debris, new byte[] { 1 });
      BackupsTestUtils.BACKUPS.putBytes(orphan, new byte[] { 2 });
      BackupsTestUtils.BACKUPS.withClock(Clock.systemUTC());

      SlaChecker.PlanResult first = check(plan);
      assertThat(first.orphans()).isEqualTo(2);
      assertThat(first.debris()).isEqualTo(1);
      BackupAlertsAssert.assertOpen("backups.storage.debris:nightly", BackupAlertSink.Severity.INFO);
      assertThat(sink.openAlert("backups.storage.debris:nightly").detail()).containsEntry("orphans", "2").containsEntry("debris", "1");

      assertThat(check(plan).orphans()).as("listed once a day").isEqualTo(-1);

      BackupsTestUtils.BACKUPS.removeIgnoringLock(debris);
      BackupsTestUtils.BACKUPS.removeIgnoringLock(orphan);
      clock.advance(Duration.ofHours(25));
      SlaChecker.PlanResult clean = check(plan);
      assertThat(clean.orphans()).isZero();
      assertThat(clean.debris()).isZero();
      assertThat(sink.isOpen("backups.storage.debris:nightly")).isFalse();
   }



   /*******************************************************************************
    ** A run that is still going (RUNNING with a fresh heartbeat) is not debris, even
    ** though its prefix has no manifest yet.
    *******************************************************************************/
   @Test
   void testRunningPrefixIsNotDebris() throws Exception
   {
      QRecord plan = plan(Map.of());
      backup(plan);
      String prefix = "qqq-backups/testapp/nightly/2026/10/12/20261012T020000Z-cafe/";
      BackupsTestUtils.BACKUPS.withClock(Clock.fixed(clock.instant().minus(Duration.ofDays(2)), java.time.ZoneOffset.UTC));
      BackupsTestUtils.BACKUPS.putBytes(prefix + "tables/author/part-00001.jsonl.gz", new byte[] { 1 });
      BackupsTestUtils.BACKUPS.withClock(Clock.systemUTC());
      Map<String, Serializable> values = new LinkedHashMap<>();
      values.put("backupPlanId", plan.getValueInteger("id"));
      values.put("runKey", "20261012T020000Z-cafe");
      values.put("status", "RUNNING");
      values.put("startDate", clock.instant());
      values.put("heartbeatDate", clock.instant());
      values.put("runPrefix", prefix);
      new BackupCatalog().insert(BackupsTables.RUN, values);

      SlaChecker.PlanResult result = check(plan);
      assertThat(result.debris()).isZero();
      assertThat(result.orphans()).isZero();
   }



   /*******************************************************************************
    ** checkAll covers only active plans, and survives a plan whose bucket is unknown.
    *******************************************************************************/
   @Test
   void testCheckAllSkipsInactivePlansAndSurvivesBadBucket() throws Exception
   {
      plan(Map.of());
      plan(Map.of("name", "Off", "planKey", "off", "isActive", false));
      plan(Map.of("name", "Bad", "planKey", "bad", "storageBucketAlias", "no-such-bucket"));
      clock.advance(Duration.ofHours(30));

      List<SlaChecker.PlanResult> results = new SlaChecker().checkAll();
      assertThat(results).extracting(SlaChecker.PlanResult::planKey).containsExactlyInAnyOrder("nightly", "bad");
      assertThat(results).extracting(SlaChecker.PlanResult::status).containsOnly(SlaChecker.LATE);
   }



   /*******************************************************************************
    ** Without a recorded success or storage the checks degrade quietly: a success
    ** run with no manifest key, and a pruned run, are not "missing".
    *******************************************************************************/
   @Test
   void testPrunedOrKeylessSuccessIsNotMissing() throws Exception
   {
      QRecord         plan   = plan(Map.of());
      BackupRunResult result = backup(plan);
      new BackupCatalog().update(BackupsTables.RUN, result.getRunId(), java.util.Collections.singletonMap("prunedDate", Instant.now()));
      BackupsTestUtils.BACKUPS.removeIgnoringLock(result.getManifestKey());
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);

      new BackupCatalog().update(BackupsTables.RUN, result.getRunId(), java.util.Collections.singletonMap("manifestKey", null));
      assertThat(check(plan).status()).isEqualTo(SlaChecker.OK);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private Integer runRow(QRecord plan, String runKey, String status, Instant heartbeat) throws Exception
   {
      Map<String, Serializable> values = new LinkedHashMap<>();
      values.put("backupPlanId", plan.getValueInteger("id"));
      values.put("runKey", runKey);
      values.put("status", status);
      values.put("startDate", heartbeat);
      values.put("heartbeatDate", heartbeat);
      values.put("runPrefix", "qqq-backups/testapp/nightly/2026/10/12/" + runKey + "/");
      return (new BackupCatalog().insert(BackupsTables.RUN, values));
   }



   /***************************************************************************
    ** Alert assertions over the recording sink.
    ***************************************************************************/
   private static final class BackupAlertsAssert
   {
      /*******************************************************************************
       ** The alert is open at that severity.
       *******************************************************************************/
      static void assertOpen(String dedupeKey, BackupAlertSink.Severity severity)
      {
         assertThat(sink.isOpen(dedupeKey)).as("alert " + dedupeKey + " is open").isTrue();
         assertThat(sink.openAlert(dedupeKey).severity()).isEqualTo(severity);
         assertThat(sink.openAlert(dedupeKey).dedupeKey()).isEqualTo(dedupeKey);
         assertThat(sink.openAlert(dedupeKey).type()).isEqualTo(dedupeKey.substring(0, dedupeKey.indexOf(':')));
      }
   }
}
