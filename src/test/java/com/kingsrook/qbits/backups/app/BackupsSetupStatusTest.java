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

package com.kingsrook.qbits.backups.app;


import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.app.BackupsSetupStatus.Item;
import com.kingsrook.qbits.backups.app.BackupsSetupStatus.State;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.processes.DefaultPlanProcess;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Setup card of the Backups home: with no plan it tells the user to run Create
 ** default plan; with a plan it checks the storage alias, the schedule, the
 ** verification and the prune bucket, and links each to its table.
 *******************************************************************************/
class BackupsSetupStatusTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Item item(List<Item> items, String label)
   {
      return (items.stream().filter(i -> i.label().equals(label)).findFirst().orElseThrow());
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static QRecord plan(Map<String, Object> values)
   {
      QRecord plan = new QRecord().withValue("name", "Nightly").withValue("isActive", true).withValue("scopeType", "INSTANCE").withValue("storageBucketAlias", "backups");
      values.forEach((k, v) -> plan.setValue(k, (java.io.Serializable) v));
      return (plan);
   }



   /*******************************************************************************
    ** No plans: the first step is the Create default plan action.
    *******************************************************************************/
   @Test
   void noPlansPointsAtTheDefaultAction()
   {
      List<Item> items = BackupsSetupStatus.load(List.of());
      assertThat(item(items, "Backup plans").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Backup plans").nextStep()).contains("Create default plan");
      assertThat(item(items, "Plan scope rules").state()).isEqualTo(State.OPTIONAL);

      String html = BackupsHomeView.render(new BackupsHomeData(List.of(), List.of(), List.of(), List.of(), List.of(), Instant.now()), "/app");
      assertThat(html).contains("Setup").contains("1 item needs attention").contains("Create default plan").contains("href=\"/app/backups_plan\"");
   }



   /*******************************************************************************
    ** A bare plan: storage resolves, but no schedule, no verification and no prune bucket.
    *******************************************************************************/
   @Test
   void aBarePlanNeedsASchedule()
   {
      List<Item> items = BackupsSetupStatus.load(List.of(plan(Map.of())), true);
      assertThat(item(items, "Backup plans").state()).isEqualTo(State.OK);
      assertThat(item(items, "Storage").state()).isEqualTo(State.OK);
      assertThat(item(items, "Schedule").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Verification").state()).isEqualTo(State.OPTIONAL);
      assertThat(item(items, "Prune bucket").state()).isEqualTo(State.DEFAULT);
   }



   /*******************************************************************************
    ** A complete plan: everything is in place.
    *******************************************************************************/
   @Test
   void aCompletePlanIsSetUp()
   {
      List<Item> items = BackupsSetupStatus.load(List.of(plan(Map.of("cronExpression", DefaultPlanProcess.CRON, "verifyCronExpression", DefaultPlanProcess.VERIFY_CRON, "pruneBucketAlias", "backups"))), true);
      assertThat(items).extracting(Item::state).doesNotContain(State.TODO, State.DEFAULT);
      assertThat(items).extracting(Item::tableName).contains(BackupsTables.PLAN, BackupsTables.PLAN_SCOPE, BackupsTables.VERIFICATION);
   }



   /*******************************************************************************
    ** Without a scheduler the crons never fire: Schedule and Verification are TODO, and a
    ** plan whose isActive is null is not counted active (as at runtime).
    *******************************************************************************/
   @Test
   void noSchedulerMeansCronsNeverFire()
   {
      QRecord complete = plan(Map.of("cronExpression", DefaultPlanProcess.CRON, "verifyCronExpression", DefaultPlanProcess.VERIFY_CRON, "pruneBucketAlias", "backups"));
      List<Item> items = BackupsSetupStatus.load(List.of(complete), false);
      assertThat(item(items, "Schedule").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Schedule").detail()).contains("No scheduler is configured");
      assertThat(item(items, "Verification").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Verification").nextStep()).contains("schedulerName");

      QRecord unset = new QRecord().withValue("name", "N").withValue("storageBucketAlias", "backups");
      assertThat(item(BackupsSetupStatus.load(List.of(unset), true), "Backup plans").state()).isEqualTo(State.TODO);
      assertThat(item(BackupsSetupStatus.load(List.of(complete)), "Schedule").state()).as("the test instance has no scheduler").isEqualTo(State.TODO);
   }



   /*******************************************************************************
    ** A host that ticks plans itself is not told it needs a scheduler; a verify cron its
    ** tick does not run is called out, and one it does run is fine.
    *******************************************************************************/
   @Test
   void hostSchedulerCountsButUnrunVerifyIsCalledOut()
   {
      QRecord complete = plan(Map.of("cronExpression", DefaultPlanProcess.CRON, "verifyCronExpression", DefaultPlanProcess.VERIFY_CRON, "pruneBucketAlias", "backups"));
      List<Item> items = BackupsSetupStatus.load(List.of(complete), false, "the website ticker", false);
      assertThat(item(items, "Schedule").state()).isEqualTo(State.OK);
      assertThat(item(items, "Schedule").detail()).contains("the website ticker");
      assertThat(item(items, "Verification").state()).isEqualTo(State.OPTIONAL);
      assertThat(item(items, "Verification").detail()).contains("Not run by the website ticker");

      assertThat(item(BackupsSetupStatus.load(List.of(complete), false, "the website ticker", true), "Verification").state()).isEqualTo(State.OK);
   }



   /*******************************************************************************
    ** An alias that does not resolve, an inactive plan and a scoped plan are called out.
    *******************************************************************************/
   @Test
   void problemsAreCalledOut()
   {
      List<Item> items = BackupsSetupStatus.load(List.of(plan(Map.of("storageBucketAlias", "nowhere", "isActive", false, "scopeType", "TABLES"))), true);
      assertThat(item(items, "Backup plans").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Storage").state()).isEqualTo(State.TODO);
      assertThat(item(items, "Storage").detail()).contains("nowhere");
      assertThat(item(items, "Plan scope rules").detail()).contains("limited by scope rules");
   }
}
