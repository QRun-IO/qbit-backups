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

package com.kingsrook.qbits.backups.health;


import java.util.Map;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.middleware.health.model.metadata.HealthCheckResult;
import com.kingsrook.qqq.middleware.health.model.metadata.HealthStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The health indicator: DOWN only when a critical, active plan is LATE, FAILING or
 ** MISSING_IN_STORAGE.
 *******************************************************************************/
class BackupsHealthIndicatorTest
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
    **
    *******************************************************************************/
   private void setStatus(QRecord plan, String status) throws Exception
   {
      new BackupCatalog().update(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of("slaStatus", status));
   }



   /*******************************************************************************
    ** UP with details when nothing critical is wrong, including a non-critical plan
    ** that is LATE; DOWN when a critical plan is LATE, FAILING or MISSING_IN_STORAGE.
    *******************************************************************************/
   @Test
   void testUpAndDown() throws Exception
   {
      BackupsHealthIndicator indicator = new BackupsHealthIndicator();
      assertThat(indicator.getName()).isEqualTo("backups");

      HealthCheckResult empty = indicator.check(QContext.getQInstance());
      assertThat(empty.getStatus()).isEqualTo(HealthStatus.UP);
      assertThat(empty.getDetails()).containsEntry("activePlans", 0);

      QRecord critical = VerifyTestSupport.plan(Map.of("critical", true));
      QRecord ordinary = VerifyTestSupport.plan(Map.of("name", "Other", "planKey", "other"));
      VerifyTestSupport.plan(Map.of("name", "Off", "planKey", "off", "isActive", false, "critical", true));

      setStatus(critical, "OK");
      setStatus(ordinary, "LATE");
      HealthCheckResult up = indicator.check(QContext.getQInstance());
      assertThat(up.getStatus()).isEqualTo(HealthStatus.UP);
      assertThat(up.getDetails()).containsEntry("activePlans", 2).doesNotContainKey("criticalPlansWithProblems");
      assertThat(up.getDurationMs()).isNotNull();

      for(String status : new String[] { "LATE", "FAILING", "MISSING_IN_STORAGE" })
      {
         setStatus(critical, status);
         HealthCheckResult down = indicator.check(QContext.getQInstance());
         assertThat(down.getStatus()).as(status).isEqualTo(HealthStatus.DOWN);
         assertThat(down.getDetails().get("criticalPlansWithProblems").toString()).contains("nightly: " + status);
      }

      setStatus(critical, "UNVERIFIED");
      assertThat(indicator.check(QContext.getQInstance()).getStatus()).as("not verified is not down").isEqualTo(HealthStatus.UP);
   }



   /*******************************************************************************
    ** On a thread with no QQQ context (a health request) the indicator makes its own
    ** and cleans up.
    *******************************************************************************/
   @Test
   void testWorksWithoutAContext() throws Exception
   {
      QInstance qInstance = QContext.getQInstance();
      QContext.clear();
      try
      {
         assertThat(new BackupsHealthIndicator().check(qInstance).getStatus()).isEqualTo(HealthStatus.UP);
         assertThat(QContext.getQInstance()).isNull();
      }
      finally
      {
         QContext.init(qInstance, new com.kingsrook.qqq.backend.core.model.session.QSession());
      }
   }
}
