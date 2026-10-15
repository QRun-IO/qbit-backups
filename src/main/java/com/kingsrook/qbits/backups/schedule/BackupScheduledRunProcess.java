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


import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.processes.BackupsProcesses;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFunctionInputMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QRecordListMetaData;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The scheduled entry point (spec 9.1, 11.1): the process a plan scheduled job
 ** runs.  The scheduler gives it the plan record and its own system session; the
 ** run is a full-instance, system-session backup (securityScope FULL, requested by
 ** system:scheduler), which is why the plan owner is validated first: a plan whose
 ** owner has gone, or lost backups.admin, is not run silently.  It raises
 ** backups.plan.owner-invalid, marks the plan OWNER_INVALID, and stops.
 **
 ** Everything goes through the run coordinator, so a trigger that finds the plan
 ** already running is skipped visibly, and a full instance queues the run.
 *******************************************************************************/
public class BackupScheduledRunProcess implements BackendStep
{
   private static final QLogger LOG = QLogger.getLogger(BackupScheduledRunProcess.class);

   public static final String NAME = "backupRunScheduledPlan";

   private static volatile Predicate<String> ownerHasAdmin = userId -> true;



   /*******************************************************************************
    ** The process metadata: not in the nav, a record process on backups_plan.
    *******************************************************************************/
   public static QProcessMetaData processMetaData()
   {
      QProcessMetaData process = new QProcessMetaData()
         .withName(NAME)
         .withLabel("Run scheduled backup")
         .withTableName(BackupsTables.PLAN)
         .withIcon(new QIcon().withName("backup"))
         .withStep(new QBackendStepMetaData()
            .withName("execute")
            .withInputData(new QFunctionInputMetaData().withRecordListMetaData(new QRecordListMetaData().withTableName(BackupsTables.PLAN)))
            .withCode(new QCodeReference(BackupScheduledRunProcess.class)));
      process.setPermissionRules(BackupsTables.permissionRules());
      return (BackupsProcesses.permission(process.withSupplementalMetaData(BackupsProcesses.durability()), BackupsPermissions.RUN));
   }



   /*******************************************************************************
    ** How the qbit asks whether a plan owner still exists and still holds
    ** backups.admin.  The default accepts any owner id; an application with a user
    ** directory installs the real check here.
    *******************************************************************************/
   public static void setOwnerHasAdminCheck(Predicate<String> check)
   {
      ownerHasAdmin = check == null ? userId -> true : check;
   }



   /*******************************************************************************
    ** Why a plan may not run on its own now, or null when it may.
    *******************************************************************************/
   public static String ownerProblem(QRecord plan)
   {
      String owner = plan.getValueString("ownerUserId");
      if(owner == null || owner.isBlank())
      {
         return ("the plan has no owner");
      }
      if(!ownerHasAdmin.test(owner))
      {
         return ("the plan owner " + owner + " no longer exists or no longer has the backups.admin permission");
      }
      return (null);
   }



   /*******************************************************************************
    ** Run the plan.
    *******************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      List<QRecord> records = input.getRecords();
      if(records == null || records.isEmpty())
      {
         throw (new QUserFacingException("No backup plan was selected or found."));
      }
      BackupCatalog catalog = new BackupCatalog();
      QRecord       plan    = catalog.get(BackupsTables.PLAN, records.get(0).getValueInteger("id")).orElseThrow(() -> new QUserFacingException("The backup plan no longer exists."));

      if(!Boolean.TRUE.equals(plan.getValueBoolean("isActive")))
      {
         LOG.info("A scheduled backup fired for a plan that is not active; nothing was run", logPair("plan", plan.getValueString("planKey")));
         output.addValue("status", "INACTIVE");
         return;
      }

      String problem = ownerProblem(plan);
      if(problem != null)
      {
         catalog.update(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of("slaStatus", "OWNER_INVALID"));
         BackupsRuntime.getAlertSink().raise("backups.plan.owner-invalid", "backups.plan.owner-invalid:" + plan.getValueString("planKey"), BackupAlertSink.Severity.WARNING,
            "A scheduled backup did not run: " + problem, Map.of("plan", plan.getValueString("planKey")));
         throw (new QUserFacingException("The scheduled backup did not run: " + problem + "."));
      }
      BackupsRuntime.getAlertSink().resolve("backups.plan.owner-invalid", "backups.plan.owner-invalid:" + plan.getValueString("planKey"));

      BackupRunResult result = new BackupRunCoordinator().runPlan(plan, "SCHEDULED", com.kingsrook.qqq.backend.core.jobs.progress.JobProgress.of(input), null);
      output.addValue("status", result.getStatus());
      output.addValue("runId", result.getRunId());
      if(!"SUCCEEDED".equals(result.getStatus()) && !"SUCCEEDED_WITH_WARNINGS".equals(result.getStatus()))
      {
         BackupsProcesses.requireSuccessfulJob(result);
         LOG.info("A scheduled backup did not succeed", logPair("plan", plan.getValueString("planKey")), logPair("status", result.getStatus()), logPair("message", result.getErrorMessage()));
      }
   }
}
