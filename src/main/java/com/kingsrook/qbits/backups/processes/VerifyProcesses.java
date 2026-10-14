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

package com.kingsrook.qbits.backups.processes;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.sla.SlaChecker;
import com.kingsrook.qbits.backups.verify.BackupVerifier;
import com.kingsrook.qbits.backups.verify.VerificationOutcome;
import com.kingsrook.qqq.backend.core.actions.processes.BackendStep;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope;
import com.kingsrook.qqq.backend.core.jobs.progress.JobPhase;
import com.kingsrook.qqq.backend.core.jobs.progress.JobProgress;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QBackendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.scheduleing.QScheduleMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import static com.kingsrook.qbits.backups.processes.BackupsProcesses.line;


/*******************************************************************************
 ** The verification and SLA processes (design spec section 10):
 ** <ul>
 **    <li>backupVerify: the record action "Verify backup" on backups_run, a standard
 **    streamed ETL.  Review lists what will be checked (nothing is read); the run
 **    does the STORAGE level and then, if that passes, the CONTENT level.  The input
 **    value verifyLevel (STORAGE, CONTENT or TEST_RESTORE) picks how far to go;
 **    CONTENT is the default and TEST_RESTORE is for the API and schedules.</li>
 **    <li>backupCheckSla: the scheduled SLA check, every slaCheckIntervalSeconds on
 **    the configured scheduler, independent of the backup trigger.  It is not in the
 **    nav.</li>
 ** </ul>
 *******************************************************************************/
public final class VerifyProcesses
{
   public static final String VERIFY    = "backupVerify";
   public static final String CHECK_SLA = "backupCheckSla";

   public static final String FIELD_VERIFY_LEVEL = "verifyLevel";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private VerifyProcesses()
   {
   }



   /*******************************************************************************
    ** The processes of this area.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      processes.add(BackupsProcesses.permission(BackupsProcesses.etl(VERIFY, "Verify backup", BackupsTables.RUN, VerifyTransform.class, VerifyLoad.class,
         "This checks that the backup's objects are all in storage, then reads every byte back, decrypts and decodes it, and compares counts and hashes with the manifest. Nothing is changed in the backup.").withSupplementalMetaData(BackupsProcesses.durability()),
         BackupsPermissions.RUN));
      processes.add(checkSlaProcess(BackupsRuntime.getConfig()));
      return (processes);
   }



   /*******************************************************************************
    ** The SLA check process.  With a scheduler configured it is scheduled every
    ** slaCheckIntervalSeconds.
    *******************************************************************************/
   public static QProcessMetaData checkSlaProcess(BackupsQBitConfig config)
   {
      QProcessMetaData process = new QProcessMetaData()
         .withName(CHECK_SLA)
         .withLabel("Check backup SLAs")
         .withStepList(List.<QStepMetaData>of(new QBackendStepMetaData().withName("check").withCode(new QCodeReference(CheckSlaStep.class))));
      process.setPermissionRules(BackupsTables.permissionRules());

      if(config != null && config.getSchedulerName() != null && config.getSlaCheckIntervalSeconds() != null)
      {
         process.setSchedule(new QScheduleMetaData()
            .withSchedulerName(config.getSchedulerName())
            .withRepeatSeconds(config.getSlaCheckIntervalSeconds())
            .withInitialDelaySeconds(30)
            .withDescription("Backup SLA check (independent of the backup trigger)"));
      }
      return (BackupsProcesses.permission(process, BackupsPermissions.RUN));
   }



   /*******************************************************************************
    ** The scheduled SLA check.
    *******************************************************************************/
   public static class CheckSlaStep implements BackendStep
   {
      /*******************************************************************************
       ** Check every active plan.
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         List<SlaChecker.PlanResult> results = new SlaChecker().checkAll();
         long attention = results.stream().filter(r -> !SlaChecker.OK.equals(r.status()) && !SlaChecker.UNKNOWN.equals(r.status())).count();
         output.addValue("plansChecked", results.size());
         output.addValue("plansNeedingAttention", attention);
      }
   }



   /*******************************************************************************
    ** Verify backup, review: which runs can be verified and how much will be read.
    *******************************************************************************/
   public static class VerifyTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine runs     = line(Status.OK, "backup will be verified", "backups will be verified", "backup was verified", "backups were verified");
      private final ProcessSummaryLine objects  = line(Status.INFO, "stored object will be checked", "stored objects will be checked", "stored object was checked", "stored objects were checked");
      private final ProcessSummaryLine files    = line(Status.INFO, "referenced file will be checked", "referenced files will be checked", "referenced file was checked", "referenced files were checked");
      private final ProcessSummaryLine noManifest = line(Status.ERROR, "backup has no manifest, so it is not usable and cannot be verified", "backups have no manifest, so they are not usable and cannot be verified",
         "backup had no manifest", "backups had no manifest");
      private final ProcessSummaryLine pruned   = line(Status.ERROR, "backup was pruned, so its objects are gone", "backups were pruned, so their objects are gone", "backup was pruned", "backups were pruned");
      private final BackupCatalog      catalog  = new BackupCatalog();



      /*******************************************************************************
       ** Look at each run without reading any backup bytes.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord run : input.getRecords())
         {
            java.io.Serializable id = run.getValue("id");
            if(run.getValueInstant("prunedDate") != null)
            {
               pruned.incrementCountAndAddPrimaryKey(id);
            }
            else if(run.getValueString("manifestKey") == null)
            {
               noManifest.incrementCountAndAddPrimaryKey(id);
            }
            else
            {
               runs.incrementCountAndAddPrimaryKey(id);
               objects.incrementCount(catalog.where(BackupsTables.ARTIFACT, "backupRunId", run.getValueInteger("id")).size());
               files.incrementCount(run.getValueInteger("filesReferenced") == null ? 0 : run.getValueInteger("filesReferenced"));
               output.getRecords().add(run);
            }
         }
      }



      /*******************************************************************************
       ** Review lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         runs.addSelfToListIfAnyCount(lines);
         objects.addSelfToListIfAnyCount(lines);
         files.addSelfToListIfAnyCount(lines);
         noManifest.addSelfToListIfAnyCount(lines);
         pruned.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Verify backup, execute: the storage level, then the content level.
    *******************************************************************************/
   public static class VerifyLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine passed   = line(Status.OK, "backup will pass verification", "backups passed verification", "backup passed verification", "backups passed verification");
      private final ProcessSummaryLine warnings = line(Status.WARNING, "backup passed with warnings", "backups passed with warnings", "backup passed with warnings", "backups passed with warnings");
      private final ProcessSummaryLine failed   = line(Status.ERROR, "backup failed verification", "backups failed verification", "backup failed verification", "backups failed verification");
      private final ProcessSummaryLine errored  = line(Status.ERROR, "backup could not be verified", "backups could not be verified", "backup could not be verified", "backups could not be verified");
      private final ProcessSummaryLine rows     = line(Status.INFO, "row was decoded and checked", "rows were decoded and checked", "row was decoded and checked", "rows were decoded and checked");
      private final ProcessSummaryLine files    = line(Status.INFO, "file was checked", "files were checked", "file was checked", "files were checked");



      /*******************************************************************************
       ** Verify each run.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         JobProgress progress = JobProgress.of(input);
         progress.phases(JobPhase.of("verify", "Verify", 100)).phase("verify").total(input.getRecords().size(), "backups").done(0);
         int done = 0;
         String level = input.getValueString(FIELD_VERIFY_LEVEL) == null ? BackupVerifier.CONTENT : input.getValueString(FIELD_VERIFY_LEVEL);
         String user  = QContext.getQSession() == null || QContext.getQSession().getUser() == null ? "system" : QContext.getQSession().getUser().getIdReference();
         for(QRecord run : input.getRecords())
         {
            JobChunkScope.checkBetweenPages();
            progress.message("Verifying backup " + run.getValueInteger("id"));
            List<VerificationOutcome> outcomes = new BackupVerifier().verifyThrough(run.getValueInteger("id"), level, "MANUAL", user);
            VerificationOutcome       last     = outcomes.get(outcomes.size() - 1);
            for(VerificationOutcome outcome : outcomes)
            {
               progress.info("Verification " + outcome.level() + ": " + outcome.status());
            }
            if(!last.passed())
            {
               progress.getSnapshot().getPhases().getFirst().setStatus(JobPhase.Status.FAILED);
               progress.error("Backup verification " + last.status());
               if(JobChunkScope.current().isPresent())
               {
                  throw (new QException("Backup verification " + last.status()));
               }
            }
            progress.done(++done);
            switch(last.status())
            {
               case "PASSED" -> passed.incrementCountAndAddPrimaryKey(run.getValue("id"));
               case "PASSED_WITH_WARNINGS" -> warnings.incrementCountAndAddPrimaryKey(run.getValue("id"));
               case "ERROR" ->
               {
                  errored.incrementCountAndAddPrimaryKey(run.getValue("id"));
                  errored.setMessageSuffix(last.messages().isEmpty() ? null : ": " + last.messages().get(0));
               }
               default ->
               {
                  failed.incrementCountAndAddPrimaryKey(run.getValue("id"));
                  failed.setMessageSuffix(last.messages().isEmpty() ? null : ": " + last.messages().get(0));
               }
            }
            for(VerificationOutcome outcome : outcomes)
            {
               rows.incrementCount((int) Math.min(Integer.MAX_VALUE, outcome.rowsChecked()));
               files.incrementCount(outcome.filesChecked());
            }
            output.getRecords().add(run);
         }
         progress.getSnapshot().getPhases().getFirst().setStatus(failed.getCount() > 0 || errored.getCount() > 0 ? JobPhase.Status.FAILED : JobPhase.Status.DONE);
         progress.flush();
      }



      /*******************************************************************************
       ** Result lines.
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         passed.addSelfToListIfAnyCount(lines);
         warnings.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         errored.addSelfToListIfAnyCount(lines);
         rows.addSelfToListIfAnyCount(lines);
         files.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
