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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.sla.SlaChecker;
import com.kingsrook.qqq.backend.core.alerts.AlertResolution;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertSinkInterface;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The backup adapter against the released core alerts API and a registered sink.
 *******************************************************************************/
class AlertsQBitAlertSinkTest
{


   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(3);
      RecordingSink.raised.clear();
      RecordingSink.resolved.clear();
      RecordingSink.heartbeats.clear();
      RecordingSink.failWith = null;
      AlertsInstanceMetaData.ofOrWithNew(QContext.getQInstance()).withSink(new QCodeReference(RecordingSink.class));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      BackupsRuntime.reset();
      QContext.clear();
   }



   /*******************************************************************************
    ** Raise, resolve and heartbeat reach the SPI with the stable dedupe key, the
    ** severity mapped by name, and the detail as the summary and attributes.
    *******************************************************************************/
   @Test
   void testRaiseResolveHeartbeat()
   {
      AlertsQBitAlertSink sink = new AlertsQBitAlertSink();
      Map<String, String> detail = new LinkedHashMap<>();
      detail.put("plan", "nightly");
      detail.put("slaHours", "26");

      sink.raise("backups.plan.missed", "backups.plan.missed:nightly", BackupAlertSink.Severity.CRITICAL, "A backup did not run in time", detail);
      sink.resolve("backups.plan.missed", "backups.plan.missed:nightly");
      sink.heartbeat("backups.plan:nightly", 93600, 0);

      assertThat(RecordingSink.raised).hasSize(1);
      var signal = RecordingSink.raised.get(0);
      assertThat(signal.getAlertTypeName()).isEqualTo("backups.plan.missed");
      assertThat(signal.getDedupKey()).isEqualTo("backups.plan.missed:nightly");
      assertThat(signal.getSeverity().name()).isEqualTo("CRITICAL");
      assertThat(signal.getTitle()).isEqualTo("A backup did not run in time");
      assertThat(signal.getSummary()).isEqualTo("plan=nightly, slaHours=26");
      assertThat(signal.getAttributes()).containsEntry("plan", "nightly").containsEntry("slaHours", "26");
      assertThat(RecordingSink.resolved).singleElement().satisfies(resolution ->
      {
         assertThat(resolution.getAlertTypeName()).isEqualTo("backups.plan.missed");
         assertThat(resolution.getDedupKey()).isEqualTo("backups.plan.missed:nightly");
         assertThat(resolution.getNote()).isEqualTo("Cleared by the Backups qbit");
      });
      assertThat(RecordingSink.heartbeats).containsExactly("backups.plan:nightly");

      sink.raise("backups.storage.debris", "k", BackupAlertSink.Severity.INFO, "no detail", null);
      assertThat(RecordingSink.raised.get(1).getSummary()).isNull();
   }



   /*******************************************************************************
    ** Without a configured sink, even with alert type metadata present, every
    ** call is a no-op.
    *******************************************************************************/
   @Test
   void testNoOpWithoutTheAlertsQBit()
   {
      AlertsInstanceMetaData.of(QContext.getQInstance()).withSink(null);
      AlertsQBitAlertSink sink = new AlertsQBitAlertSink();
      long failures = QAlerts.getProducerFailureCount();
      sink.raise(null, "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      sink.resolve(null, "k");
      sink.heartbeat(null, 1, 1);
      assertThat(QAlerts.getProducerFailureCount()).isEqualTo(failures);
      assertThat(RecordingSink.raised).isEmpty();
      assertThat(RecordingSink.resolved).isEmpty();
      assertThat(RecordingSink.heartbeats).isEmpty();
   }



   /*******************************************************************************
    ** The same adapter follows the current instance and a sink installed later.
    *******************************************************************************/
   @Test
   void testLateInstallationAndInstanceSwitch()
   {
      var original = QContext.getQInstance();
      QContext.setQInstance(new QInstance());
      AlertsQBitAlertSink sink = new AlertsQBitAlertSink();
      assertThat(sink.isAlertsQBitInstalled()).isFalse();
      sink.raise("backups.plan.missed", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      assertThat(RecordingSink.raised).isEmpty();
      sink.registerAlertTypes(QContext.getQInstance());
      assertThat(sink.isAlertsQBitInstalled()).isFalse();
      AlertsInstanceMetaData.of(QContext.getQInstance()).withSink(new QCodeReference(RecordingSink.class));
      sink.raise("backups.plan.missed", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      assertThat(RecordingSink.raised).hasSize(1);
      assertThat(RecordingSink.raised.get(0).getSummary()).isNull();
      QContext.setQInstance(original);
      assertThat(sink.isAlertsQBitInstalled()).isTrue();
   }



   /*******************************************************************************
    ** Sink and adapter failures never throw into the backup.
    *******************************************************************************/
   @Test
   void testFailuresAreSwallowed()
   {
      long failures = QAlerts.getProducerFailureCount();
      RecordingSink.failWith = new IllegalStateException("alerts down");
      AlertsQBitAlertSink broken = new AlertsQBitAlertSink();
      broken.raise("t", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      broken.resolve("t", "k");
      broken.heartbeat("m", 1, 1);
      assertThat(RecordingSink.raised).isEmpty();
      assertThat(QAlerts.getProducerFailureCount()).isEqualTo(failures + 3);
      broken.raise("t", "k", null, "bad severity", Map.of());
      broken.raise("t", "k", null, "bad severity again", Map.of());
      assertThat(broken.registerAlertTypes(null)).isZero();
   }



   /*******************************************************************************
    ** The backup alert types are declared with the Alerts qbit.
    *******************************************************************************/
   @Test
   void testRegisterAlertTypes() throws Exception
   {
      QInstance instance = new QInstance();
      AlertsQBitAlertSink sink = new AlertsQBitAlertSink();
      int added = sink.registerAlertTypes(instance);
      var types = new ArrayList<>(AlertsInstanceMetaData.of(instance).getAlertTypes().values());
      assertThat(added).isEqualTo(BackupAlerts.alertTypes().size()).isEqualTo(9);
      assertThat(types).extracting(t -> t.getName()).containsExactly(
         "backups.plan.missed", "backups.run.failed", "backups.run.missing-in-storage", "backups.files.missing", "backups.verify.failed",
         "backups.verify.overdue", "backups.plan.owner-invalid", "backups.storage.debris", "backups.restore.completed");
      assertThat(types).allSatisfy(t ->
      {
         assertThat(t.getGroupName()).isEqualTo("Backups");
         assertThat(t.getHowToFix()).isNotBlank();
         assertThat(t.getLabel()).isNotBlank();
      });
      assertThat(types.get(2).getDefaultSeverity().name()).isEqualTo("CRITICAL");
      assertThat(sink.registerAlertTypes(instance)).isZero();
      new QInstanceValidator().validate(QContext.getQInstance());
   }



   /*******************************************************************************
    ** Alert-only types get notify switched off on the Alerts qbit's type row (once
    ** the row exists); types that notify are left alone.
    *******************************************************************************/
   @Test
   void testAlertOnlyTypesAreNotSentToTheBell() throws Exception
   {
      QContext.getQInstance().addTable(new QTableMetaData().withName("alerts_type").withBackendName(BackupsTestUtils.BACKEND).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("name", QFieldType.STRING))
         .withField(new QFieldMetaData("notify", QFieldType.BOOLEAN)));
      InsertInput insert = new InsertInput("alerts_type");
      insert.setRecords(List.of(
         new QRecord().withValue("name", BackupAlerts.STORAGE_DEBRIS).withValue("notify", true),
         new QRecord().withValue("name", BackupAlerts.PLAN_MISSED).withValue("notify", true)));
      new InsertAction().execute(insert);

      AlertsQBitAlertSink sink = new AlertsQBitAlertSink();
      sink.raise(BackupAlerts.PLAN_MISSED, "k", BackupAlertSink.Severity.ERROR, "x", Map.of());
      sink.raise(BackupAlerts.STORAGE_DEBRIS, "k", BackupAlertSink.Severity.INFO, "x", Map.of());

      QueryInput query = new QueryInput("alerts_type");
      Map<String, Boolean> notify = new LinkedHashMap<>();
      new QueryAction().execute(query).getRecords().forEach(r -> notify.put(r.getValueString("name"), r.getValueBoolean("notify")));
      assertThat(notify).containsEntry(BackupAlerts.STORAGE_DEBRIS, false).containsEntry(BackupAlerts.PLAN_MISSED, true);
   }



   /*******************************************************************************
    ** Default binding preserves a host-provided backup sink.
    *******************************************************************************/
   @Test
   void testDetectionAndBinding()
   {
      assertThat(AlertsQBitAlertSink.bindIfAbsent()).isFalse();
      assertThat(BackupsRuntime.getAlertSink()).isSameAs(VerifyTestSupport.sink);
      BackupsRuntime.setAlertSink(null);
      assertThat(AlertsQBitAlertSink.bindIfAbsent()).isTrue();
      assertThat(BackupsRuntime.getAlertSink()).isInstanceOf(AlertsQBitAlertSink.class);
      assertThat(AlertsQBitAlertSink.bindIfAbsent()).isFalse();
   }



   /*******************************************************************************
    ** End to end: the SLA checker's alerts reach the Alerts qbit SPI and resolve.
    *******************************************************************************/
   @Test
   void testSlaAlertsThroughTheAdapter() throws Exception
   {
      BackupsRuntime.setAlertSink(new AlertsQBitAlertSink());
      QRecord plan = VerifyTestSupport.plan(Map.of());
      VerifyTestSupport.backup(plan);
      assertThat(RecordingSink.heartbeats).contains("backups.plan:nightly");

      VerifyTestSupport.clock.advance(Duration.ofHours(30));
      new SlaChecker().check(VerifyTestSupport.reload(plan));
      assertThat(RecordingSink.raised).anySatisfy(s ->
      {
         assertThat(s.getAlertTypeName()).isEqualTo("backups.plan.missed");
         assertThat(s.getDedupKey()).isEqualTo("backups.plan.missed:nightly");
         assertThat(s.getSeverity().name()).isEqualTo("ERROR");
      });

      VerifyTestSupport.backup(plan);
      new SlaChecker().check(VerifyTestSupport.reload(plan));
      assertThat(RecordingSink.resolved).anyMatch(r -> r.getAlertTypeName().equals("backups.plan.missed") && r.getDedupKey().equals("backups.plan.missed:nightly"));
   }



   /*******************************************************************************
    ** Sink loaded by the real QCodeLoader through AlertsInstanceMetaData.
    *******************************************************************************/
   public static class RecordingSink implements AlertSinkInterface
   {
      static final List<AlertSignal> raised = new ArrayList<>();
      static final List<AlertResolution> resolved = new ArrayList<>();
      static final List<String> heartbeats = new ArrayList<>();
      static RuntimeException failWith;



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void raise(AlertSignal signal)
      {
         if(failWith != null)
         {
            throw (failWith);
         }
         raised.add(signal);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void resolve(AlertResolution resolution)
      {
         if(failWith != null)
         {
            throw (failWith);
         }
         resolved.add(resolution);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void heartbeat(String heartbeatKey, Instant at)
      {
         if(failWith != null)
         {
            throw (failWith);
         }
         heartbeats.add(heartbeatKey);
      }
   }
}
