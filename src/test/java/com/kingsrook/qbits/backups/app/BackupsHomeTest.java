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


import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupEngine;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppChildMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Backups home: null-safe against plans that have no SLA status, honest empty
 ** states, working links, and the nav and relations shape.
 *******************************************************************************/
class BackupsHomeTest
{
   private QInstance instance;



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      instance = BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(5);
   }



   /*******************************************************************************
    ** A plan with a null slaStatus (a fresh plan nobody has run) used to throw an NPE.
    *******************************************************************************/
   @Test
   void testPlanWithoutSlaStatusDoesNotThrow() throws Exception
   {
      new BackupCatalog().insert(BackupsTables.PLAN, new LinkedHashMap<>(Map.of("name", "Fresh", "planKey", "fresh", "isActive", true, "scopeType", "INSTANCE", "storageBucketAlias", "backups")));

      BackupsHomeData data = BackupsHomeData.load();
      assertThat(data.plansNeedingAttention()).isEmpty();
      assertThat(data.overallState()).isEqualTo("OK");
      assertThat(data.latestTestRestore(null)).isNull();
      assertThat(data.successfulRuns(null)).isEmpty();
      assertThat(data.retentionCounts(null)).isEmpty();

      String html = BackupsHomeView.render(data, "/app");
      assertThat(html).contains("Fresh").contains("No successful backup yet").contains("Not run yet").contains("Unknown");
   }



   /*******************************************************************************
    ** Empty states are honest, and the whole home renders with no data at all.
    *******************************************************************************/
   @Test
   void testEmptyStates()
   {
      String html = BackupsHomeView.render(new BackupsHomeData(List.of(), List.of(), List.of(), List.of(), List.of(), Instant.now()), "/app");
      assertThat(html).contains("No backup plans yet").contains("Setup: <a").contains("No plan: nothing is backed up.").doesNotContain("Nothing needs attention").contains("No test restore yet")
         .contains("Nothing has run yet").contains("Trends appear after the first successful run").contains("Retention starts once runs exist");
      assertThat(BackupsHomeView.renderHealthCard(new BackupsHomeData(List.of(), List.of(), List.of(), List.of(), List.of(), Instant.now()), "/app")).contains("No backup plans yet");
   }



   /*******************************************************************************
    ** After a real run the home links to records under the table path prefix, and
    ** a plan whose last success is older than its SLA shows as late.
    *******************************************************************************/
   @Test
   void testRealRunLinksAndLateness() throws Exception
   {
      BackupCatalog catalog = new BackupCatalog();
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("name", "Nightly", "planKey", "nightly", "isActive", true, "scopeType", "INSTANCE", "storageBucketAlias", "backups", "slaHours", 26));
      Integer planId = catalog.insert(BackupsTables.PLAN, values);
      new BackupEngine().execute(BackupRunFactory.forPlan(catalog.get(BackupsTables.PLAN, planId).orElseThrow(), "MANUAL"));

      BackupsHomeData fresh = BackupsHomeData.load();
      assertThat(fresh.plansNeedingAttention()).isEmpty();
      String html = BackupsHomeView.render(fresh, "/app");
      assertThat(html).contains("href=\"/app/backups_plan/" + planId + "\"").contains("href=\"/app/backups_run/1\"").contains("Succeeded with warnings");

      BackupsHomeData later = new BackupsHomeData(fresh.plans(), fresh.recentRuns(), List.of(), List.of(), List.of(), fresh.now().plus(Duration.ofHours(30)));
      assertThat(later.effectiveStatus(later.plans().get(0))).isEqualTo("LATE");
      assertThat(later.plansNeedingAttention()).hasSize(1);
      assertThat(BackupsHomeView.render(later, "/app")).contains("Needs attention").contains("Late");
      assertThat(later.overallState()).isEqualTo("ATTENTION");
   }



   /*******************************************************************************
    ** The Setup card and the Needs attention card never contradict each other: a plan
    ** whose storage aliases do not resolve is a Setup fault, and it is listed under
    ** Needs attention too, with the same count.
    *******************************************************************************/
   @Test
   void testUnresolvedAliasAppearsInSetupAndNeedsAttention() throws Exception
   {
      BackupsRuntime.getConfig().withStorageResolver(alias -> BackupsTestUtils.BACKUPS);
      new BackupCatalog().insert(BackupsTables.PLAN, new LinkedHashMap<>(Map.of("name", "Offsite", "planKey", "offsite", "isActive", true, "scopeType", "INSTANCE",
         "storageBucketAlias", "backupsAws", "pruneBucketAlias", "backupsAwsPrune")));

      //////////////////////////////////////////////////////////////////////////////
      // the aliases resolved when the plan was saved; now the bucket is gone      //
      //////////////////////////////////////////////////////////////////////////////
      BackupsRuntime.getConfig().withStorageResolver(alias -> "backups".equals(alias) ? BackupsTestUtils.BACKUPS : null);

      BackupsHomeData data = BackupsHomeData.load();
      List<BackupsSetupStatus.Item> setupTodo = BackupsSetupStatus.load(data.plans()).stream().filter(i -> i.state() == BackupsSetupStatus.State.TODO).toList();
      assertThat(setupTodo).extracting(BackupsSetupStatus.Item::label).contains("Storage");

      String html = BackupsHomeView.render(data, "/app");
      String attention = html.substring(html.indexOf(">Needs attention<"));
      attention = attention.substring(0, attention.indexOf("All runs"));
      assertThat(attention).doesNotContain("Nothing needs attention").contains("backupsAws").contains("backupsAwsPrune");

      ///////////////////////////////////////////////////////////////////////////////
      // every Setup TODO is listed under Needs attention (counts agree: 2 and 2)   //
      ///////////////////////////////////////////////////////////////////////////////
      assertThat(html).contains(setupTodo.size() + " items need attention.");
      for(BackupsSetupStatus.Item item : setupTodo)
      {
         assertThat(attention).contains("Setup: <a").contains(item.label());
      }
      assertThat(attention.split("<li>", -1).length - 1).isEqualTo(setupTodo.size() + data.plansNeedingAttention().size() + data.failedRuns().size());
   }



   /*******************************************************************************
    ** The links follow the configured table path prefix.
    *******************************************************************************/
   @Test
   void testPathPrefixFollowsConfig()
   {
      assertThat(BackupsApp.pathPrefix()).isEqualTo("/app");
      BackupsRuntime.getConfig().withTablePathPrefix("/backups");
      assertThat(BackupsApp.pathPrefix()).isEqualTo("/backups");
   }



   /*******************************************************************************
    ** The nav lists only the main tables; sub-tables are child lists on their
    ** parents; the one home widget is on the app.
    *******************************************************************************/
   @Test
   void testNavAndRelations()
   {
      QAppMetaData app = instance.getApp("backups");
      assertThat(app.getChildren().stream().map(QAppChildMetaData::getName)).containsExactlyInAnyOrder(
         BackupsTables.PLAN, BackupsTables.RUN, BackupsTables.RESTORE_RUN, BackupsTables.VERIFICATION);
      assertThat(app.getWidgets()).containsExactly(BackupsApp.HOME_WIDGET);

      for(String child : List.of("artifacts", "verifications", "restores"))
      {
         assertThat(instance.getTable(BackupsTables.RUN).getSections().stream().map(s -> s.getName())).contains(child);
      }
      assertThat(instance.getTable(BackupsTables.PLAN).getSections().stream().map(s -> s.getName())).contains("scopeRules", "runs");
      assertThat(instance.getTable(BackupsTables.RESTORE_RUN).getSections().stream().map(s -> s.getName())).contains("restoreTables");
      assertThat(QContext.getQInstance().getWidget("backupsArtifactJoinRun")).isNotNull();
   }



   /*******************************************************************************
    ** A parent app lists the Backups app as its child.
    *******************************************************************************/
   @Test
   void testAttachesToParentApp() throws Exception
   {
      QInstance withParent = new QInstance();
      withParent.withInstanceDefaultAuthentication(instance.getAuthentication());
      withParent.addBackend(instance.getBackend(BackupsTestUtils.BACKEND));
      withParent.addApp(new QAppMetaData().withName("admin").withLabel("Admin"));
      new com.kingsrook.qbits.backups.BackupsQBitProducer().withNamespace("backups")
         .withQBitConfig(new com.kingsrook.qbits.backups.BackupsQBitConfig().withBackendName(BackupsTestUtils.BACKEND).withParentAppName("admin")
            .withStorageResolver(alias -> BackupsTestUtils.BACKUPS))
         .produce(withParent);

      assertThat(withParent.getApp("admin").getChildren().stream().map(QAppChildMetaData::getName)).contains("backups");
      assertThat(withParent.getApp("backups").getParentAppName()).isEqualTo("admin");
   }
}
