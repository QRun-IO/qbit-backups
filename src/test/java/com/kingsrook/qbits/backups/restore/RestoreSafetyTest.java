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

package com.kingsrook.qbits.backups.restore;


import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.scope.BackupTableMetaData;
import com.kingsrook.qbits.backups.scope.TableRole;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The safety rails of restore (spec 9.4) and who may run it (spec 11).
 *******************************************************************************/
class RestoreSafetyTest
{
   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(12);
   }



   /*******************************************************************************
    ** A restore is refused while a backup that covers the same tables is running.
    *******************************************************************************/
   @Test
   void testRefusesWhileABackupOfTheSameTablesIsRunning() throws Exception
   {
      BackupRunResult run    = RestoreTestSupport.backup(Map.of());
      Integer         planId = new BackupCatalog().where(BackupsTables.PLAN, "planKey", "nightly").get(0).getValueInteger("id");

      Map<String, Serializable> live = new LinkedHashMap<>();
      live.put("backupPlanId", planId);
      live.put("runKey", "live-one");
      live.put("status", "RUNNING");
      live.put("startDate", BackupsRuntime.getClock().instant());
      live.put("heartbeatDate", BackupsRuntime.getClock().instant());
      Integer liveId = new BackupCatalog().insert(BackupsTables.RUN, live);

      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_INSERT_NEW, "blogPost");
      assertThat(new RestoreEngine().plan(request).errors()).anyMatch(e -> e.contains("is running"));
      assertThatThrownBy(() -> new RestoreEngine().execute(request)).hasMessageContaining("is running");
      assertThat(new BackupCatalog().where(BackupsTables.RESTORE_RUN, "sourceBackupRunId", run.getRunId())).as("nothing was started").isEmpty();

      new BackupCatalog().update(BackupsTables.RUN, liveId, Map.of("heartbeatDate", BackupsRuntime.getClock().instant().minusSeconds(3600)));
      assertThat(new RestoreEngine().plan(request).errors()).as("a run with an old heartbeat is not running").isEmpty();
   }



   /*******************************************************************************
    ** REPLACE needs the typed confirmation: the table name, or REPLACE n TABLES.
    *******************************************************************************/
   @Test
   void testReplaceNeedsTheTypedConfirmation() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());

      RestoreRequest one = RestoreTestSupport.request(run, RestoreRequest.MODE_REPLACE, "blogPost");
      assertThat(new RestoreEngine().plan(one).errors()).anyMatch(e -> e.contains("type blogPost"));
      assertThat(new RestoreEngine().plan(one.withConfirmationText("blogpost")).errors()).as("case matters").isNotEmpty();
      assertThat(new RestoreEngine().plan(one.withConfirmationText("blogPost")).errors()).isEmpty();

      RestoreRequest two = RestoreTestSupport.request(run, RestoreRequest.MODE_REPLACE, "author", "blogPost");
      assertThat(new RestoreEngine().plan(two).errors()).anyMatch(e -> e.contains("REPLACE 2 TABLES"));
      assertThat(new RestoreEngine().plan(two.withConfirmationText("REPLACE 2 TABLES")).errors()).isEmpty();

      assertThat(new RestoreEngine().plan(RestoreTestSupport.request(run, RestoreRequest.MODE_REPLACE, "blogPost").withConfirmationText("blogPost")
         .withIdStrategy(RestoreRequest.IDS_REMAP)).errors()).anyMatch(e -> e.contains("REPLACE cannot be combined with new ids"));
   }



   /*******************************************************************************
    ** REPLACE also needs backups.admin, not just backups.restore.
    *******************************************************************************/
   @Test
   void testReplaceNeedsAdmin() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      BackupsRuntime.getConfig().withOpenWhenAnonymous(false);
      RestoreRequest replace = RestoreTestSupport.request(run, RestoreRequest.MODE_REPLACE, "blogPost").withConfirmationText("blogPost");

      QContext.setQSession(new QSession().withPermissions("backups.view", "backups.restore"));
      assertThat(new RestoreEngine().plan(RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost")).errors()).isEmpty();
      assertThat(new RestoreEngine().plan(replace).errors()).anyMatch(e -> e.contains("backups.admin"));

      QContext.setQSession(new QSession().withPermissions("backups.view", "backups.restore", "backups.admin"));
      assertThat(new RestoreEngine().plan(replace).errors()).isEmpty();
   }



   /*******************************************************************************
    ** Restores never touch SECRET or backups tables, and temporary (EPHEMERAL) tables
    ** only when they are named.
    *******************************************************************************/
   @Test
   void testNeverRestoresSecretBackupsOrUnnamedEphemeralTables() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of("includeRoles", "DATA,AUDIT,EPHEMERAL"));
      assertThat(RestoreTestSupport.manifest(run).table("userSession")).isNotNull();

      RestoreEngine.Plan all = new RestoreEngine().plan(new RestoreRequest().withSourceRunId(run.getRunId()));
      assertThat(all.errors()).isEmpty();
      assertThat(all.order()).containsExactly("author", "blogPost");
      assertThat(all.notes()).anyMatch(n -> n.contains("userSession") && n.contains("temporary"));

      RestoreEngine.Plan named = new RestoreEngine().plan(new RestoreRequest().withSourceRunId(run.getRunId()).withTables(List.of("userSession")));
      assertThat(named.order()).containsExactly("userSession");
      assertThat(named.warnings()).anyMatch(w -> w.contains("temporary data"));

      assertThat(new RestoreEngine().plan(new RestoreRequest().withSourceRunId(run.getRunId()).withTables(List.of("backups_plan"))).errors())
         .anyMatch(e -> e.contains("not in this backup"));

      BackupTableMetaData.ofOrWithNew(QContext.getQInstance().getTable("author")).withRole(TableRole.SECRET);
      RestoreEngine.Plan secret = new RestoreEngine().plan(new RestoreRequest().withSourceRunId(run.getRunId()).withTables(List.of("author")));
      assertThat(secret.errors()).anyMatch(e -> e.contains("SECRET") && e.contains("never touch"));
   }



   /*******************************************************************************
    ** Security: without backups.restore nobody can run a restore, through the process
    ** (the core permission check) or by calling the engine.  With the permission they can.
    *******************************************************************************/
   @Test
   void testRestoreNeedsThePermission() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreTestSupport.mutate();
      int rowsBefore = RestoreTestSupport.rows("blogPost").size();

      BackupsRuntime.getConfig().withOpenWhenAnonymous(false);
      QContext.getQInstance().setEnforcePermissionsInCoreActions(true);
      QContext.setQSession(new QSession().withPermissions("backups.view", "backups.run"));

      RunProcessInput input = new RunProcessInput();
      input.setProcessName("backupRestore");
      input.addValue("tables", "blogPost");
      input.addValue("mode", "UPSERT");
      assertThatThrownBy(() -> new RunProcessAction().execute(input)).isInstanceOf(QPermissionDeniedException.class).hasMessageContaining("backups.restore");

      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost");
      assertThatThrownBy(() -> new RestoreEngine().execute(request)).hasMessageContaining("backups.restore");
      assertThat(new RestoreEngine().review(request).getErrors()).anyMatch(e -> e.contains("backups.restore"));

      QContext.setQSession(new QSession());
      assertThat(RestoreTestSupport.rows("blogPost")).as("nothing was written").hasSize(rowsBefore);

      QContext.setQSession(new QSession().withPermissions("backups.view", "backups.restore"));
      assertThat(new RestoreEngine().plan(request).errors()).isEmpty();
   }


   /*******************************************************************************
    ** The core restore permission is required even in anonymous demo mode, and
    ** Review reports it before a restore can create a run or write any data.
    *******************************************************************************/
   @Test
   void testCoreRestorePermissionIsRequiredWhenAnonymous() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      QContext.setQSession(new QSession());
      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT, "blogPost");
      assertThat(new RestoreEngine().plan(request).errors()).anyMatch(e -> e.contains("backups.restore"));
      assertThatThrownBy(() -> new RestoreEngine().execute(request)).hasMessageContaining("backups.restore");
      assertThatThrownBy(() -> new StandardRestoreWriter().insert(QContext.getQInstance().getTable("blogPost"),
         List.of(new com.kingsrook.qqq.backend.core.model.data.QRecord().withValue("id", 99)), request, null)).hasMessageContaining("backups.restore");
   }



   /*******************************************************************************
    ** Job-page cancellation reaches the restore worker and its catalog status.
    *******************************************************************************/
   @Test
   void testDurableCancellation() throws Exception
   {
      BackupRunResult run = RestoreTestSupport.backup(Map.of());
      RestoreRequest request = RestoreTestSupport.request(run, RestoreRequest.MODE_UPSERT).withPreRestoreBackup(false);
      try(var scope = com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope.open(null, () ->
      {
         throw (new com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException(com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException.Reason.CANCEL));
      }))
      {
         assertThatThrownBy(() -> new RestoreEngine().execute(request)).isInstanceOf(com.kingsrook.qqq.backend.core.jobs.plans.JobInterruptedException.class);
      }
      assertThat(new BackupCatalog().where(BackupsTables.RESTORE_RUN, "status", "CANCELLED")).hasSize(1);
   }

}
