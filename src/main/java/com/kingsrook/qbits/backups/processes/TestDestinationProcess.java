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
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.plan.DestinationProbe;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import static com.kingsrook.qbits.backups.processes.BackupsProcesses.line;


/*******************************************************************************
 ** Test destination (spec 9): a record action on backups_plan.  Result shows the
 ** write, read, list and delete of a probe object (head only on a locked bucket),
 ** and the capability report of the bucket.  See DestinationProbe.
 *******************************************************************************/
public final class TestDestinationProcess
{
   public static final String NAME = "backupTestDestination";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private TestDestinationProcess()
   {
   }



   /*******************************************************************************
    ** The process.
    *******************************************************************************/
   public static QProcessMetaData process()
   {
      return (BackupsProcesses.permission(BackupsProcesses.etl(NAME, "Test destination", BackupsTables.PLAN, Transform.class, Load.class,
         "This will write a small probe object to the destination, read it back, list it and delete it. On a locked bucket it only reads."), BackupsPermissions.ADMIN));
   }



   /*******************************************************************************
    ** Review: nothing is written yet.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine plans = line(Status.OK, "plan destination will be tested", "plan destinations will be tested", "plan destination was tested", "plan destinations were tested");
      private final ProcessSummaryLine locked = line(Status.INFO, "destination is object locked: the test will only read (no probe object is written)",
         "destinations are object locked: the tests will only read (no probe object is written)", "destination was object locked: the test only read", "destinations were object locked: the tests only read");



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord plan : input.getRecords())
         {
            plans.incrementCountAndAddPrimaryKey(plan.getValue("id"));
            String mode = plan.getValueString("objectLockMode");
            if(mode != null && !"NONE".equals(mode))
            {
               locked.incrementCount();
            }
            output.getRecords().add(plan);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         plans.addSelfToListIfAnyCount(lines);
         locked.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Result: the checks and the capability report.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final List<ProcessSummaryLineInterface> lines = new ArrayList<>();



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord plan : input.getRecords())
         {
            DestinationProbe.Report report = DestinationProbe.probe(plan);
            String                  name   = plan.getValueString("name");
            for(DestinationProbe.Check check : report.checks())
            {
               ProcessSummaryLine line = line(check.ok() ? Status.OK : Status.ERROR, check.name() + " succeeded: " + check.detail(), check.name() + " succeeded: " + check.detail(),
                  check.name() + " succeeded: " + check.detail(), check.name() + " succeeded: " + check.detail());
               if(!check.ok())
               {
                  line = line(Status.ERROR, check.name() + " failed: " + check.detail(), check.name() + " failed: " + check.detail(),
                     check.name() + " failed: " + check.detail(), check.name() + " failed: " + check.detail());
               }
               line.setCount(1);
               line.setMessageSuffix(" (" + name + ")");
               lines.add(line);
            }
            ProcessSummaryLine capabilities = line(Status.INFO, "Capabilities: " + String.join(", ", report.capabilities()), "Capabilities: " + String.join(", ", report.capabilities()),
               "Capabilities: " + String.join(", ", report.capabilities()), "Capabilities: " + String.join(", ", report.capabilities()));
            capabilities.setCount(1);
            lines.add(capabilities);
            for(String warning : report.warnings())
            {
               ProcessSummaryLine w = line(Status.WARNING, warning, warning, warning, warning);
               w.setCount(1);
               lines.add(w);
            }
            output.getRecords().add(plan);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         return (new ArrayList<>(lines));
      }
   }
}
