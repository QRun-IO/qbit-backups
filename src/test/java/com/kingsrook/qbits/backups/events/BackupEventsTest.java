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

package com.kingsrook.qbits.backups.events;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** ESB events are fire and forget: published for run outcomes, never allowed to
 ** fail a backup, and silent when no destination is configured.
 *******************************************************************************/
class BackupEventsTest
{
   private final List<String>                    types = new ArrayList<>();
   private final List<Map<String, Serializable>> datas = new ArrayList<>();



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(3);
      BackupEvents.setPublisher((type, data) ->
      {
         types.add(type);
         datas.add(data);
      });
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      BackupEvents.setPublisher(null);
   }



   /*******************************************************************************
    ** A successful run publishes run.succeeded; a failed one run.failed.
    *******************************************************************************/
   @Test
   void testRunEvents() throws Exception
   {
      QRecord         plan   = VerifyTestSupport.plan(Map.of());
      BackupRunResult result = VerifyTestSupport.backup(plan);
      assertThat(types).containsExactly("qqq.backups.run.succeeded");
      assertThat(datas.get(0)).containsEntry("plan", "nightly").containsEntry("run", result.getRunKey()).containsEntry("rows", result.getRows());

      BackupsTestUtils.BACKUPS.withFailWritesFor(key -> key.contains("/tables/"));
      VerifyTestSupport.backup(plan);
      assertThat(types).containsExactly("qqq.backups.run.succeeded", "qqq.backups.run.failed");
   }



   /*******************************************************************************
    ** Cancelled runs publish nothing; restores publish restore.completed.
    *******************************************************************************/
   @Test
   void testCancelledAndRestore()
   {
      BackupEvents.runFinished("nightly", "r1", "CANCELLED", 0, 0);
      BackupEvents.runFinished("nightly", "r2", "TIMED_OUT", 0, 0);
      BackupEvents.restoreCompleted("rr1", "SUCCEEDED", 12);
      assertThat(types).containsExactly("qqq.backups.run.failed", "qqq.backups.restore.completed");
      assertThat(datas.get(1)).containsEntry("restoreRun", "rr1").containsEntry("rows", 12L);
   }



   /*******************************************************************************
    ** A publisher that throws never fails the run.
    *******************************************************************************/
   @Test
   void testPublishFailureNeverFailsTheBackup() throws Exception
   {
      BackupEvents.setPublisher((type, data) ->
      {
         throw (new IllegalStateException("bus down"));
      });
      BackupRunResult result = VerifyTestSupport.backup(VerifyTestSupport.plan(Map.of()));
      assertThat(result.getStatus()).startsWith("SUCCEEDED");
   }



   /*******************************************************************************
    ** With no destination configured nothing is published; with one, the ESB is
    ** tried and a missing or unconfigured ESB is swallowed.
    *******************************************************************************/
   @Test
   void testEsbDefault() throws Exception
   {
      BackupEvents.setPublisher(null);
      BackupEvents.publish("qqq.backups.run.succeeded", Map.of());

      BackupsRuntime.getConfig().withEsbDestinationName("backups-events");
      BackupEvents.publish("qqq.backups.run.succeeded", Map.of("plan", "nightly"));
      BackupEvents.publish("qqq.backups.run.succeeded", Map.of("plan", "nightly"));
      assertThat(VerifyTestSupport.backup(VerifyTestSupport.plan(Map.of())).getStatus()).startsWith("SUCCEEDED");
   }
}
