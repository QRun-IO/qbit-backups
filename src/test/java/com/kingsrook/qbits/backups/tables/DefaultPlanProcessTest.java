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

package com.kingsrook.qbits.backups.tables;


import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.plan.BackupValidatorReset;
import com.kingsrook.qbits.backups.processes.DefaultPlanProcess;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The "Create default plan" table action: it asks for a storage bucket alias,
 ** creates the Nightly full plan through the normal insert, refuses an alias that
 ** does not resolve, and is insert-only (a second run changes nothing, an edited
 ** plan survives).
 *******************************************************************************/
class DefaultPlanProcessTest
{
   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupValidatorReset.reset();
      QContext.getQSession().withPermissions(BackupsPermissions.ADMIN);
   }



   /*******************************************************************************
    ** Run the action through Review and then Result.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private List<ProcessSummaryLine> run(String alias, boolean execute) throws QException
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(DefaultPlanProcess.NAME);
      input.addValue(DefaultPlanProcess.FIELD_ALIAS, alias);
      //////////////////////////////////////////////////////////////////////////
      // the first call stops at the input form; the next runs Review (full     //
      // validation), the last executes and gives the Result                    //
      //////////////////////////////////////////////////////////////////////////
      RunProcessOutput output = new RunProcessAction().execute(input);
      input.addValue("processUUID", output.getProcessUUID());
      input.setStartAfterStep("input");
      output = new RunProcessAction().execute(input);
      if(!execute)
      {
         return ((List<ProcessSummaryLine>) output.getValues().get(StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY));
      }
      input.setStartAfterStep("review");
      output = new RunProcessAction().execute(input);
      return ((List<ProcessSummaryLine>) output.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY));
   }



   /*******************************************************************************
    ** The action is a table action that needs no selected records and backups.admin.
    *******************************************************************************/
   @Test
   void isATableActionForAdmins() throws Exception
   {
      var process = QContext.getQInstance().getProcess(DefaultPlanProcess.NAME);
      assertThat(process.getTableName()).isEqualTo(BackupsTables.PLAN);
      assertThat(process.getMinInputRecords()).isEqualTo(0);
      assertThat(((com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData) process.getStep("input")).getFormFields().stream().map(f -> f.getName())).containsExactly(DefaultPlanProcess.FIELD_ALIAS);
   }



   /*******************************************************************************
    ** Review previews without writing; Result creates the plan, and the plan works.
    *******************************************************************************/
   @Test
   void createsTheNightlyFullPlan() throws Exception
   {
      List<ProcessSummaryLine> review = run("backups", false);
      assertThat(review).anySatisfy(line -> assertThat(line.getPluralFutureMessage()).contains("will be created"));
      assertThat(catalog.query(BackupsTables.PLAN, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).as("Review writes nothing").isEmpty();

      List<ProcessSummaryLine> result = run("backups", true);
      assertThat(result).singleElement().satisfies(line -> assertThat(line.getStatus().name()).isEqualTo("OK"));

      QRecord plan = catalog.where(BackupsTables.PLAN, "planKey", DefaultPlanProcess.PLAN_KEY).get(0);
      assertThat(plan.getValueString("name")).isEqualTo("Nightly full");
      assertThat(plan.getValueString("storageBucketAlias")).isEqualTo("backups");
      assertThat(plan.getValueBoolean("isActive")).isTrue();
      assertThat(plan.getValueString("scopeType")).isEqualTo("INSTANCE");
      assertThat(plan.getValueString("cronExpression")).isEqualTo(DefaultPlanProcess.CRON);
      assertThat(plan.getValueString("verifyCronExpression")).isEqualTo(DefaultPlanProcess.VERIFY_CRON);
      assertThat(plan.getValueInteger("slaHours")).isEqualTo(26);
      assertThat(plan.getValueString("format")).isEqualTo("JSONL");
   }



   /*******************************************************************************
    ** A plan made by the action is a working plan: it runs and backs up the instance.
    *******************************************************************************/
   @Test
   void theCreatedPlanRuns() throws Exception
   {
      BackupsTestUtils.loadBlog(3);
      run("backups", true);
      QRecord plan = catalog.where(BackupsTables.PLAN, "planKey", DefaultPlanProcess.PLAN_KEY).get(0);
      var result = new com.kingsrook.qbits.backups.run.BackupRunCoordinator().runPlan(plan, "MANUAL");
      assertThat(result.getStatus()).isIn("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS");
   }



   /*******************************************************************************
    ** Insert-only: a second run creates nothing, and an edited plan keeps its edit.
    *******************************************************************************/
   @Test
   void isInsertOnly() throws Exception
   {
      run("backups", true);
      Integer id = catalog.where(BackupsTables.PLAN, "planKey", DefaultPlanProcess.PLAN_KEY).get(0).getValueInteger("id");
      catalog.update(BackupsTables.PLAN, id, Map.of("slaHours", 48, "name", "Our nightly"));

      List<ProcessSummaryLine> review = run("backups", false);
      assertThat(review).singleElement().satisfies(line -> assertThat(line.getMessage()).contains("already exists"));
      run("backups", true);

      assertThat(catalog.query(BackupsTables.PLAN, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).hasSize(1);
      QRecord plan = catalog.get(BackupsTables.PLAN, id).orElseThrow();
      assertThat(plan.getValueInteger("slaHours")).isEqualTo(48);
      assertThat(plan.getValueString("name")).isEqualTo("Our nightly");
   }



   /*******************************************************************************
    ** An alias the host does not provide is refused in Review, and nothing is created.
    *******************************************************************************/
   @Test
   void refusesAnUnknownAlias() throws Exception
   {
      List<ProcessSummaryLine> review = run("no-such-bucket", false);
      assertThat(review).anySatisfy(line ->
      {
         assertThat(line.getStatus().name()).isEqualTo("ERROR");
         assertThat(String.join(" ", line.getBulletsOfText())).contains("no-such-bucket");
      });
      run("no-such-bucket", true);
      assertThat(catalog.query(BackupsTables.PLAN, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter())).isEmpty();
   }
}
