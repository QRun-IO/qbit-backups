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
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.processes.BackupsProcesses;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.verify.BackupVerifier;
import com.kingsrook.qbits.backups.verify.VerificationOutcome;
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
 ** The scheduled verify entry point: the process the verify scheduled job of a
 ** plan (backups_plan.verifyCronExpression) runs.  Until this class existed the
 ** verify job was created but pointed at a process nobody had registered, so a
 ** verify cron never verified anything.
 **
 ** It verifies the plan's latest successful run, through the level the plan names
 ** (verifyLevel, CONTENT when unset), stopping at the first level that does not
 ** pass.  Findings, alerts and the plan's last-verified fields are written by the
 ** verifier, exactly as for Verify backup on a run.  A plan that has no
 ** successful run yet has nothing to verify, and says so.
 *******************************************************************************/
public class BackupScheduledVerifyProcess implements BackendStep
{
   private static final QLogger LOG = QLogger.getLogger(BackupScheduledVerifyProcess.class);

   public static final String NAME = BackupPlanVerifySyncToScheduledJobProcess.VERIFY_PROCESS_NAME;

   public static final String SCHEDULER_USER = "system:scheduler";



   /*******************************************************************************
    ** The process metadata: not in the nav, a record process on backups_plan.
    *******************************************************************************/
   public static QProcessMetaData processMetaData()
   {
      QProcessMetaData process = new QProcessMetaData()
         .withName(NAME)
         .withLabel("Run scheduled verification")
         .withTableName(BackupsTables.PLAN)
         .withIcon(new QIcon().withName("verified"))
         .withStep(new QBackendStepMetaData()
            .withName("execute")
            .withInputData(new QFunctionInputMetaData().withRecordListMetaData(new QRecordListMetaData().withTableName(BackupsTables.PLAN)))
            .withCode(new QCodeReference(BackupScheduledVerifyProcess.class)));
      process.setPermissionRules(BackupsTables.permissionRules());
      return (BackupsProcesses.permission(process, BackupsPermissions.RUN));
   }



   /*******************************************************************************
    ** The level a plan verifies to: its verifyLevel, CONTENT when unset or unknown.
    *******************************************************************************/
   public static String levelOf(QRecord plan)
   {
      String level = plan.getValueString("verifyLevel");
      return (level != null && BackupVerifier.LEVELS.contains(level) ? level : BackupVerifier.CONTENT);
   }



   /*******************************************************************************
    ** Verify the latest successful run of the plan.
    *******************************************************************************/
   @Override
   public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
   {
      List<QRecord> records = input.getRecords();
      if(records == null || records.isEmpty())
      {
         throw (new QUserFacingException("No backup plan was selected or found."));
      }
      QRecord plan = new BackupCatalog().get(BackupsTables.PLAN, records.get(0).getValueInteger("id"))
         .orElseThrow(() -> new QUserFacingException("The backup plan no longer exists."));

      if(!Boolean.TRUE.equals(plan.getValueBoolean("isActive")))
      {
         LOG.info("A scheduled verification fired for a plan that is not active; nothing was verified", logPair("plan", plan.getValueString("planKey")));
         output.addValue("status", "INACTIVE");
         return;
      }

      Integer runId = plan.getValueInteger("lastSuccessRunId");
      if(runId == null)
      {
         LOG.info("A scheduled verification fired for a plan with no successful backup yet; nothing was verified", logPair("plan", plan.getValueString("planKey")));
         output.addValue("status", "NO_BACKUP");
         return;
      }

      String                    level    = levelOf(plan);
      List<VerificationOutcome> outcomes = new BackupVerifier().verifyThrough(runId, level, "SCHEDULED", SCHEDULER_USER);
      VerificationOutcome       last     = outcomes.get(outcomes.size() - 1);
      output.addValue("status", last.status());
      output.addValue("runId", runId);
      output.addValue("level", level);
      if(!last.passed())
      {
         LOG.info("A scheduled verification did not pass", logPair("plan", plan.getValueString("planKey")), logPair("runId", runId), logPair("status", last.status()));
      }
   }
}
