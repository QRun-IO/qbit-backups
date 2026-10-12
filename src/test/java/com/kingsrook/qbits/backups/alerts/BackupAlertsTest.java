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


import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.RecordingAlertSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The alert catalog and its helpers: the spec table, stable dedupe keys, and
 ** helpers that never throw into the caller.
 *******************************************************************************/
class BackupAlertsTest
{
   private RecordingAlertSink sink;



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp()
   {
      BackupsRuntime.reset();
      sink = new RecordingAlertSink();
      BackupsRuntime.setAlertSink(sink);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      BackupsRuntime.reset();
   }



   /*******************************************************************************
    ** Every alert of the spec table (section 10.4) is declared, once, with a default
    ** severity and a way to fix it.
    *******************************************************************************/
   @Test
   void testTheSpecTable()
   {
      Set<String> names = BackupAlerts.alertTypes().stream().map(BackupAlerts.AlertType::name).collect(Collectors.toSet());
      assertThat(names).containsExactlyInAnyOrder("backups.plan.missed", "backups.run.failed", "backups.run.missing_in_storage", "backups.files.missing",
         "backups.verify.failed", "backups.verify.overdue", "backups.plan.owner_invalid", "backups.storage.debris", "backups.restore.completed");
      assertThat(BackupAlerts.alertTypes()).allSatisfy(t -> assertThat(t.howToFix()).isNotBlank());
      assertThat(BackupAlerts.alertTypes().stream().filter(t -> t.name().equals(BackupAlerts.RUN_MISSING)).findFirst().orElseThrow().defaultSeverity())
         .isEqualTo(BackupAlertSink.Severity.CRITICAL);
   }



   /*******************************************************************************
    ** Dedupe keys are the type plus the plan (or plan and level), never a value.
    *******************************************************************************/
   @Test
   void testStableKeys()
   {
      assertThat(BackupAlerts.key(BackupAlerts.PLAN_MISSED, "nightly")).isEqualTo("backups.plan.missed:nightly");
      assertThat(BackupAlerts.verifyKey("nightly", "CONTENT")).isEqualTo("backups.verify.failed:nightly:CONTENT");

      BackupAlerts.raise(BackupAlerts.FILES_MISSING, "nightly", BackupAlertSink.Severity.WARNING, "t", "missing", "3", "odd");
      BackupAlerts.raise(BackupAlerts.FILES_MISSING, "nightly", BackupAlertSink.Severity.WARNING, "t", "missing", "4");
      assertThat(sink.raises).hasSize(2).allSatisfy(r -> assertThat(r.dedupeKey()).isEqualTo("backups.files.missing:nightly"));
      assertThat(sink.raises.get(0).detail()).containsExactly(Map.entry("missing", "3"));
      assertThat(sink.open).hasSize(1);

      BackupAlerts.resolve(BackupAlerts.FILES_MISSING, "nightly");
      assertThat(sink.open).isEmpty();
   }



   /*******************************************************************************
    ** A restore is never silent: info, or error when it failed.
    *******************************************************************************/
   @Test
   void testRestoreCompleted()
   {
      BackupAlerts.restoreCompleted("20261012T030000Z-r1", "SUCCEEDED", "nightly", 42);
      BackupAlerts.restoreCompleted("20261012T030000Z-r2", "FAILED", null, 0);
      assertThat(sink.raises).extracting(RecordingAlertSink.Raised::severity).containsExactly(BackupAlertSink.Severity.INFO, BackupAlertSink.Severity.ERROR);
      assertThat(sink.raises.get(0).detail()).containsEntry("rows", "42").containsEntry("plan", "nightly");
      assertThat(sink.raises.get(0).type()).isEqualTo("backups.restore.completed");
   }



   /*******************************************************************************
    ** A sink that throws never fails the caller.
    *******************************************************************************/
   @Test
   void testASinkThatThrowsIsSwallowed()
   {
      BackupsRuntime.setAlertSink(new NoOpBackupAlertSink()
      {
         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public void raise(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail)
         {
            throw (new IllegalStateException("down"));
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public void resolve(String type, String dedupeKey)
         {
            throw (new IllegalStateException("down"));
         }
      });
      BackupAlerts.raise("t", "p", BackupAlertSink.Severity.INFO, "x");
      BackupAlerts.raiseWithKey("t", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      BackupAlerts.resolve("t", "p");
      BackupAlerts.resolveKey("t", "k");

      new NoOpBackupAlertSink().raise("t", "k", BackupAlertSink.Severity.INFO, "x", Map.of());
      new NoOpBackupAlertSink().resolve("t", "k");
      new NoOpBackupAlertSink().heartbeat("m", 1, 1);
   }
}
