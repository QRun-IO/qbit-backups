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

package com.kingsrook.qbits.backups.schedule;


import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsQBitProducer;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.LifecycleTestSupport.RecordingAlertSink;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.plan.BackupValidatorReset;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.actions.processes.QProcessCallbackFactory;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.common.TimeZonePossibleValueSourceMetaDataProvider;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.simple.SimpleSchedulerMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.scheduledjobs.ScheduledJob;
import com.kingsrook.qqq.backend.core.model.scheduledjobs.ScheduledJobsMetaDataProvider;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryRecordStore;
import com.kingsrook.qqq.backend.core.scheduler.QScheduleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The plan schedule (spec 9.1): one scheduledJob per active plan, one more for the
 ** verify cron, kept in step as the plan changes, and the scheduled entry point that
 ** runs the engine as the system session after checking the plan owner.
 *******************************************************************************/
class BackupPlanScheduleTest
{
   private static final String BACKEND = "memory";

   private final BackupCatalog catalog = new BackupCatalog();
   private RecordingAlertSink alerts;



   /*******************************************************************************
    ** An instance with the scheduled jobs tables, a scheduler, and the qbit configured
    ** with that scheduler.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsRuntime.reset();
      MemoryRecordStore.getInstance().reset();
      BackupsTestUtils.BACKUPS.clear();
      BackupValidatorReset.reset();

      QInstance qInstance = new QInstance();
      qInstance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withType(QAuthenticationType.FULLY_ANONYMOUS).withName("anon"));
      qInstance.addBackend(new QBackendMetaData().withName(BACKEND).withBackendType(MemoryBackendModule.class));
      qInstance.addScheduler(new SimpleSchedulerMetaData().withName("simple"));
      qInstance.addTable(new QTableMetaData().withName("author").withBackendName(BACKEND).withPrimaryKeyField("id")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("name", QFieldType.STRING)));
      new ScheduledJobsMetaDataProvider().defineAll(qInstance, BACKEND, null);
      qInstance.addPossibleValueSource(new TimeZonePossibleValueSourceMetaDataProvider().produce());

      BackupsQBitConfig config = new BackupsQBitConfig().withBackendName(BACKEND).withInstanceName("testapp").withSchedulerName("simple")
         .withStorageResolver(alias -> "backups".equals(alias) ? BackupsTestUtils.BACKUPS : null).withOpenWhenAnonymous(true);
      new BackupsQBitProducer().withQBitConfig(config).withNamespace("backups").produce(qInstance);
      new QInstanceValidator().validate(qInstance);

      QContext.init(qInstance, new QSession());
      QScheduleManager.initInstance(qInstance, QSystemUserSession::new);

      alerts = new RecordingAlertSink();
      BackupsRuntime.setAlertSink(alerts);
      InsertInput authors = new InsertInput("author");
      authors.setRecords(List.of(new QRecord().withValue("id", 1).withValue("name", "Ada")));
      new InsertAction().execute(authors);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void tearDown()
   {
      try
      {
         QScheduleManager.getInstance().stop();
         QScheduleManager.getInstance().unInit();
      }
      catch(IllegalStateException e)
      {
         // not initialized
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private Integer insertPlan(Map<String, Serializable> overrides) throws QException
   {
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("name", "Nightly", "planKey", "nightly", "isActive", true, "scopeType", "INSTANCE", "storageBucketAlias", "backups"));
      values.putAll(overrides);
      return (catalog.insert(BackupsTables.PLAN, values));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private List<ScheduledJob> jobs() throws QException
   {
      return (new QueryAction().execute(new QueryInput(ScheduledJob.TABLE_NAME).withIncludeAssociations(true)).getRecordEntities(ScheduledJob.class));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String param(ScheduledJob job, String key)
   {
      return (job.getJobParameters().stream().filter(p -> p.getKey().equals(key)).findFirst().map(p -> p.getValue()).orElse(null));
   }



   /*******************************************************************************
    ** An active plan with a cron gets one job; its verify cron gets a second; both are
    ** keyed to the plan, point at the right process, and follow the plan as it changes.
    *******************************************************************************/
   @Test
   void testPlansOwnTheirScheduledJobs() throws Exception
   {
      Integer planId = insertPlan(Map.of("cronExpression", "0 0 2 * * ?", "cronTimeZoneId", "America/Chicago", "verifyCronExpression", "0 0 4 ? * SUN"));

      List<ScheduledJob> jobs = jobs();
      assertThat(jobs).hasSize(2);
      ScheduledJob run = jobs.stream().filter(j -> BackupPlanSyncToScheduledJobProcess.FOREIGN_KEY_TYPE.equals(j.getForeignKeyType())).findFirst().orElseThrow();
      ScheduledJob verify = jobs.stream().filter(j -> BackupPlanVerifySyncToScheduledJobProcess.FOREIGN_KEY_TYPE.equals(j.getForeignKeyType())).findFirst().orElseThrow();

      assertThat(run.getForeignKeyType()).isEqualTo("backups_plan");
      assertThat(run.getForeignKeyValue()).isEqualTo(String.valueOf(planId));
      assertThat(run.getCronExpression()).isEqualTo("0 0 2 * * ?");
      assertThat(run.getCronTimeZoneId()).isEqualTo("America/Chicago");
      assertThat(run.getIsActive()).isTrue();
      assertThat(run.getSchedulerName()).isEqualTo("simple");
      assertThat(param(run, "processName")).isEqualTo(BackupScheduledRunProcess.NAME);
      assertThat(param(run, "recordId")).isEqualTo(String.valueOf(planId));

      assertThat(verify.getForeignKeyType()).isEqualTo("backups_plan_verify");
      assertThat(verify.getForeignKeyValue()).isEqualTo(String.valueOf(planId));
      assertThat(verify.getCronExpression()).isEqualTo("0 0 4 ? * SUN");
      assertThat(param(verify, "processName")).isEqualTo(BackupPlanVerifySyncToScheduledJobProcess.VERIFY_PROCESS_NAME);

      ///////////////////////////////////
      // a new cron follows the plan   //
      ///////////////////////////////////
      UpdateInput update = new UpdateInput(BackupsTables.PLAN);
      update.setRecords(List.of(new QRecord().withValue("id", planId).withValue("cronExpression", "0 30 3 * * ?")));
      update.withPermissionBypass("test");
      new UpdateAction().execute(update);
      assertThat(jobs()).hasSize(2).anyMatch(j -> "0 30 3 * * ?".equals(j.getCronExpression()));

      ////////////////////////////////////////////////////////////////
      // an inactive plan keeps its jobs but switches them off       //
      ////////////////////////////////////////////////////////////////
      UpdateInput off = new UpdateInput(BackupsTables.PLAN);
      off.setRecords(List.of(new QRecord().withValue("id", planId).withValue("isActive", false)));
      off.withPermissionBypass("test");
      new UpdateAction().execute(off);
      assertThat(jobs()).hasSize(2).allSatisfy(j -> assertThat(j.getIsActive()).isFalse());

      UpdateInput on = new UpdateInput(BackupsTables.PLAN);
      on.setRecords(List.of(new QRecord().withValue("id", planId).withValue("isActive", true)));
      on.withPermissionBypass("test");
      new UpdateAction().execute(on);
      assertThat(jobs()).hasSize(2).allSatisfy(j -> assertThat(j.getIsActive()).isTrue());

      ///////////////////////////////////////////////
      // deleting the plan deletes its jobs        //
      ///////////////////////////////////////////////
      DeleteInput delete = new DeleteInput(BackupsTables.PLAN);
      delete.setPrimaryKeys(new java.util.ArrayList<>(List.of(planId)));
      delete.withPermissionBypass("test");
      new DeleteAction().execute(delete);
      assertThat(jobs()).isEmpty();
   }



   /*******************************************************************************
    ** A plan with no cron is manual only: no job.  A verify cron alone makes only the
    ** verify job.
    *******************************************************************************/
   @Test
   void testManualOnlyPlansHaveNoJob() throws Exception
   {
      insertPlan(Map.of());
      assertThat(jobs()).isEmpty();

      insertPlan(Map.of("name", "Verify only", "planKey", "verify-only", "verifyCronExpression", "0 0 4 ? * SUN"));
      assertThat(jobs()).singleElement().satisfies(j -> assertThat(j.getForeignKeyType()).isEqualTo("backups_plan_verify"));

      insertPlan(Map.of("name", "Draft", "planKey", "draft", "isActive", false, "cronExpression", "0 0 2 * * ?"));
      assertThat(jobs()).as("an inactive plan gets no new job").hasSize(1);
   }



   /*******************************************************************************
    ** Run the scheduled entry point for a plan, as the scheduler would.
    *******************************************************************************/
   private RunProcessOutput runScheduled(Integer planId) throws QException
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(BackupScheduledRunProcess.NAME);
      input.setCallback(QProcessCallbackFactory.forPrimaryKey("id", planId));
      input.setFrontendStepBehavior(RunProcessInput.FrontendStepBehavior.SKIP);
      return (new RunProcessAction().execute(input));
   }



   /*******************************************************************************
    ** The scheduled entry point runs the engine as the system scheduler session: a
    ** full-instance run, requested by system:scheduler, triggered SCHEDULED.
    *******************************************************************************/
   @Test
   void testScheduledEntryPointRunsAsTheSystem() throws Exception
   {
      Integer planId = insertPlan(Map.of("cronExpression", "0 0 2 * * ?", "cronTimeZoneId", "UTC", "ownerUserId", "jmaes"));
      RunProcessOutput output = runScheduled(planId);
      assertThat(output.getException()).isEmpty();
      assertThat(output.getValues().get("status")).isIn("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS");

      QRecord run = catalog.where(BackupsTables.RUN, "backupPlanId", planId).get(0);
      assertThat(run.getValueString("trigger")).isEqualTo("SCHEDULED");
      assertThat(run.getValueString("requestedByUserId")).isEqualTo("system:scheduler");
      assertThat(run.getValueString("securityScope")).isEqualTo("FULL");
      assertThat(run.getValueString("manifestKey")).isNotNull();
      assertThat(BackupsTestUtils.BACKUPS.getBytes(run.getValueString("manifestKey"))).isNotNull();
      assertThat(catalog.get(BackupsTables.PLAN, planId).orElseThrow().getValueInteger("lastSuccessRunId")).isEqualTo(run.getValueInteger("id"));
   }



   /*******************************************************************************
    ** A plan whose owner is gone does not run silently: the plan says OWNER_INVALID,
    ** a warning is raised, and nothing is written.  An inactive plan does nothing.
    *******************************************************************************/
   @Test
   void testScheduledRunRefusesAnInvalidOwner() throws Exception
   {
      Integer planId = insertPlan(Map.of("cronExpression", "0 0 2 * * ?", "cronTimeZoneId", "UTC", "ownerUserId", "departed"));
      BackupScheduledRunProcess.setOwnerHasAdminCheck(owner -> !"departed".equals(owner));

      assertThatThrownBy(() -> runScheduled(planId)).hasMessageContaining("did not run").hasMessageContaining("departed");
      assertThat(catalog.where(BackupsTables.RUN, "backupPlanId", planId)).isEmpty();
      assertThat(BackupsTestUtils.BACKUPS.list("")).isEmpty();
      assertThat(catalog.get(BackupsTables.PLAN, planId).orElseThrow().getValueString("slaStatus")).isEqualTo("OWNER_INVALID");
      assertThat(alerts.raised).containsExactly("backups.plan.owner_invalid|backups.plan.owner_invalid:nightly");

      BackupScheduledRunProcess.setOwnerHasAdminCheck(null);
      Integer noOwner = insertPlan(Map.of("name", "Ownerless", "planKey", "ownerless", "isActive", true));
      assertThatThrownBy(() -> runScheduled(noOwner)).hasMessageContaining("the plan has no owner");

      UpdateInput off = new UpdateInput(BackupsTables.PLAN);
      off.setRecords(List.of(new QRecord().withValue("id", planId).withValue("isActive", false)));
      off.withPermissionBypass("test");
      new UpdateAction().execute(off);
      RunProcessOutput inactive = runScheduled(planId);
      assertThat(inactive.getValues().get("status")).isEqualTo("INACTIVE");
      assertThat(catalog.where(BackupsTables.RUN, "backupPlanId", planId)).isEmpty();
   }
}
