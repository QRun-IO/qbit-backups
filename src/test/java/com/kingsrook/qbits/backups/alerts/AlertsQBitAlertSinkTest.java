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

package com.kingsrook.qbits.backups.alerts;


import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.alerts.fakeapi.AlertsInstanceMetaData;
import com.kingsrook.qbits.backups.alerts.fakeapi.QAlertTypeMetaData;
import com.kingsrook.qbits.backups.alerts.fakeapi.QAlerts;
import com.kingsrook.qbits.backups.sla.SlaChecker;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Alerts qbit adapter, bound reflectively.  The SPI is not in QQQ 4.1.0-RC.4,
 ** so these tests run it against a test double with the same shape as the real
 ** com.kingsrook.qqq.backend.core.alerts package (QRun-IO/qqq PR #1108).
 *******************************************************************************/
class AlertsQBitAlertSinkTest
{
   private static final String FAKE_API = "com.kingsrook.qbits.backups.alerts.fakeapi";



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(3);
      QAlerts.reset();
      QAlertTypeMetaData.ADDED.clear();
      AlertsInstanceMetaData.installed = true;
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      AlertsInstanceMetaData.installed = false;
      QAlerts.reset();
   }



   /*******************************************************************************
    ** Raise, resolve and heartbeat reach the SPI with the stable dedupe key, the
    ** severity mapped by name, and the detail as the summary and attributes.
    *******************************************************************************/
   @Test
   void testRaiseResolveHeartbeat()
   {
      AlertsQBitAlertSink sink = new AlertsQBitAlertSink(FAKE_API);
      Map<String, String> detail = new LinkedHashMap<>();
      detail.put("plan", "nightly");
      detail.put("slaHours", "26");

      sink.raise("backups.plan.missed", "backups.plan.missed:nightly", BackupAlertSink.Severity.CRITICAL, "A backup did not run in time", detail);
      sink.resolve("backups.plan.missed", "backups.plan.missed:nightly");
      sink.heartbeat("backups.plan:nightly", 93600, 0);

      assertThat(QAlerts.RAISED).hasSize(1);
      var signal = QAlerts.RAISED.get(0);
      assertThat(signal.alertTypeName).isEqualTo("backups.plan.missed");
      assertThat(signal.dedupKey).isEqualTo("backups.plan.missed:nightly");
      assertThat(signal.severity.name()).isEqualTo("CRITICAL");
      assertThat(signal.title).isEqualTo("A backup did not run in time");
      assertThat(signal.summary).isEqualTo("plan=nightly, slaHours=26");
      assertThat(signal.attributes).containsEntry("plan", "nightly").containsEntry("slaHours", "26");
      assertThat(QAlerts.RESOLVED).containsExactly("backups.plan.missed|backups.plan.missed:nightly|Cleared by the Backups qbit");
      assertThat(QAlerts.HEARTBEATS).containsExactly("backups.plan:nightly");

      sink.raise("backups.storage.debris", "k", BackupAlertSink.Severity.INFO, "no detail", null);
      assertThat(QAlerts.RAISED.get(1).summary).isNull();
   }



   /*******************************************************************************
    ** Without the Alerts qbit installed (no alerts meta-data on the instance) every
    ** call is a no-op.
    *******************************************************************************/
   @Test
   void testNoOpWithoutTheAlertsQBit()
   {
      AlertsInstanceMetaData.installed = false;
      AlertsQBitAlertSink sink = new AlertsQBitAlertSink(FAKE_API);
      sink.raise("t", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      sink.resolve("t", "k");
      sink.heartbeat("m", 1, 1);
      assertThat(QAlerts.RAISED).isEmpty();
      assertThat(QAlerts.RESOLVED).isEmpty();
      assertThat(QAlerts.HEARTBEATS).isEmpty();
   }



   /*******************************************************************************
    ** A broken or missing SPI never throws into the backup.
    *******************************************************************************/
   @Test
   void testFailuresAreSwallowed()
   {
      AlertsQBitAlertSink missing = new AlertsQBitAlertSink("no.such.package");
      missing.raise("t", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      missing.resolve("t", "k");
      missing.heartbeat("m", 1, 1);
      assertThat(missing.registerAlertTypes(QContext.getQInstance())).isZero();

      QAlerts.failWith = new IllegalStateException("alerts down");
      AlertsQBitAlertSink broken = new AlertsQBitAlertSink(FAKE_API);
      broken.raise("t", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      broken.resolve("t", "k");
      broken.heartbeat("m", 1, 1);
      assertThat(QAlerts.RAISED).isEmpty();
   }



   /*******************************************************************************
    ** The backup alert types are declared with the Alerts qbit.
    *******************************************************************************/
   @Test
   void testRegisterAlertTypes()
   {
      int added = new AlertsQBitAlertSink(FAKE_API).registerAlertTypes(QContext.getQInstance());
      assertThat(added).isEqualTo(BackupAlerts.alertTypes().size()).isEqualTo(9);
      assertThat(QAlertTypeMetaData.ADDED).extracting(t -> t.name).containsExactly(
         "backups.plan.missed", "backups.run.failed", "backups.run.missing_in_storage", "backups.files.missing", "backups.verify.failed",
         "backups.verify.overdue", "backups.plan.owner_invalid", "backups.storage.debris", "backups.restore.completed");
      assertThat(QAlertTypeMetaData.ADDED).allSatisfy(t ->
      {
         assertThat(t.groupName).isEqualTo("Backups");
         assertThat(t.howToFix).isNotBlank();
         assertThat(t.label).isNotBlank();
      });
      assertThat(QAlertTypeMetaData.ADDED.get(2).defaultSeverity.name()).isEqualTo("CRITICAL");
   }



   /*******************************************************************************
    ** Detection: the SPI is present or not by class name; the real SPI is not in
    ** RC.4, so nothing is bound by default there.
    *******************************************************************************/
   @Test
   void testDetectionAndBinding()
   {
      assertThat(AlertsQBitAlertSink.isPresent(FAKE_API)).isTrue();
      assertThat(AlertsQBitAlertSink.isPresent("no.such.package")).isFalse();

      boolean realSpiPresent = AlertsQBitAlertSink.isPresent(AlertsQBitAlertSink.DEFAULT_API_PACKAGE);
      assertThat(AlertsQBitAlertSink.bindIfPresent()).isEqualTo(realSpiPresent);
      if(realSpiPresent)
      {
         assertThat(BackupsRuntime.getAlertSink()).isInstanceOf(AlertsQBitAlertSink.class);
         assertThat(AlertsQBitAlertSink.bindIfPresent()).as("an already bound sink is not replaced").isFalse();
      }
   }



   /*******************************************************************************
    ** End to end: the SLA checker's alerts reach the Alerts qbit SPI and resolve.
    *******************************************************************************/
   @Test
   void testSlaAlertsThroughTheAdapter() throws Exception
   {
      BackupsRuntime.setAlertSink(new AlertsQBitAlertSink(FAKE_API));
      QRecord plan = VerifyTestSupport.plan(Map.of());
      VerifyTestSupport.backup(plan);
      assertThat(QAlerts.HEARTBEATS).contains("backups.plan:nightly");

      VerifyTestSupport.clock.advance(Duration.ofHours(30));
      new SlaChecker().check(VerifyTestSupport.reload(plan));
      assertThat(QAlerts.RAISED).anySatisfy(s ->
      {
         assertThat(s.alertTypeName).isEqualTo("backups.plan.missed");
         assertThat(s.dedupKey).isEqualTo("backups.plan.missed:nightly");
         assertThat(s.severity.name()).isEqualTo("ERROR");
      });

      VerifyTestSupport.backup(plan);
      new SlaChecker().check(VerifyTestSupport.reload(plan));
      assertThat(QAlerts.RESOLVED).anyMatch(r -> r.startsWith("backups.plan.missed|backups.plan.missed:nightly|"));
   }
}
