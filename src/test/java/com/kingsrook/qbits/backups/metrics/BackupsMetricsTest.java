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

package com.kingsrook.qbits.backups.metrics;


import java.time.Duration;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.verify.BackupVerifier;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Prometheus text, computed from the tables.
 *******************************************************************************/
class BackupsMetricsTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(3);
   }



   /*******************************************************************************
    ** The five gauges, labeled with the instance and the plan, from real runs.
    *******************************************************************************/
   @Test
   void testRender() throws Exception
   {
      QRecord         plan   = VerifyTestSupport.plan(Map.of());
      BackupRunResult result = VerifyTestSupport.backup(plan);
      new BackupVerifier().verify(result.getRunId(), BackupVerifier.CONTENT, "MANUAL", "t");

      String text   = BackupsMetrics.render();
      String labels = "{instance_name=\"testapp\",plan=\"nightly\"}";
      long   now    = VerifyTestSupport.clock.instant().getEpochSecond();
      assertThat(text).contains("# TYPE qqq_backup_last_success_timestamp_seconds gauge")
         .contains("qqq_backup_last_success_timestamp_seconds" + labels + " " + now + "\n")
         .contains("qqq_backup_last_run_failed" + labels + " 0\n")
         .contains("qqq_backup_sla_seconds" + labels + " 93600\n")
         .contains("qqq_backup_last_verify_success_timestamp_seconds" + labels + " " + now + "\n")
         .contains("qqq_backup_last_success_bytes" + labels + " ");

      BackupsTestUtils.BACKUPS.withFailWritesFor(key -> key.contains("/tables/"));
      VerifyTestSupport.backup(plan);
      assertThat(BackupsMetrics.render()).contains("qqq_backup_last_run_failed" + labels + " 1\n");
   }



   /*******************************************************************************
    ** Cached for 60 seconds; label values are escaped; the config flag is read.
    *******************************************************************************/
   @Test
   void testCacheEscapeAndFlag() throws Exception
   {
      assertThat(BackupsMetrics.isEnabled()).isFalse();
      BackupsRuntime.getConfig().withPrometheusEndpoint(true);
      assertThat(BackupsMetrics.isEnabled()).isTrue();
      assertThat(BackupsMetrics.ENDPOINT_PATH).isEqualTo("/metrics/backups");

      String empty = BackupsMetrics.renderCached();
      assertThat(empty).doesNotContain("{instance_name");

      VerifyTestSupport.backup(VerifyTestSupport.plan(Map.of()));
      VerifyTestSupport.clock.advance(Duration.ofSeconds(30));
      assertThat(BackupsMetrics.renderCached()).as("cached").isEqualTo(empty);
      VerifyTestSupport.clock.advance(Duration.ofSeconds(31));
      assertThat(BackupsMetrics.renderCached()).contains("plan=\"nightly\"");

      assertThat(BackupsMetrics.escape("a\"b\\c\nd")).isEqualTo("a\\\"b\\\\c\\nd");
      assertThat(BackupsMetrics.escape(null)).isEmpty();
   }
}
