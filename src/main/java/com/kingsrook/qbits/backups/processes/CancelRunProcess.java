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


import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
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
 ** Cancel (spec 9): a record action on a running backup run, and on a running
 ** restore run.  The cancel is cooperative: it sets the run row to CANCELLING, and
 ** the run, which looks at its own row at every page, stops at the next page and
 ** ends CANCELLED.  A queued run is cancelled at once, and a run that has lost its
 ** heartbeat (nothing is running it) is closed straight away.
 *******************************************************************************/
public final class CancelRunProcess
{
   public static final String NAME         = "backupCancelRun";
   public static final String NAME_RESTORE = "backupCancelRestoreRun";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CancelRunProcess()
   {
   }



   /*******************************************************************************
    ** The two processes.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      return (List.of(
         BackupsProcesses.permission(BackupsProcesses.etl(NAME, "Cancel", BackupsTables.RUN, Transform.class, Load.class,
            "This asks the selected runs to stop. A run stops at its next page."), BackupsPermissions.RUN),
         BackupsProcesses.permission(BackupsProcesses.etl(NAME_RESTORE, "Cancel", BackupsTables.RESTORE_RUN, Transform.class, Load.class,
            "This asks the selected restores to stop. A restore stops at its next page."), BackupsPermissions.RESTORE)));
   }



   /*******************************************************************************
    ** What should happen to a run, by its row.
    *******************************************************************************/
   enum Action
   {
      ASK_TO_STOP, CANCEL_NOW, CLOSE_STALE, NOT_CANCELLABLE
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   static Action actionFor(QRecord run)
   {
      String status = run.getValueString("status");
      if("QUEUED".equals(status))
      {
         return (Action.CANCEL_NOW);
      }
      if("RUNNING".equals(status))
      {
         Instant heartbeat = run.getValueInstant("heartbeatDate");
         boolean live = heartbeat != null && heartbeat.isAfter(BackupsRuntime.getClock().instant().minus(BackupRunCoordinator.HEARTBEAT_STALE));
         return (live ? Action.ASK_TO_STOP : Action.CLOSE_STALE);
      }
      return (Action.NOT_CANCELLABLE);
   }



   /*******************************************************************************
    ** Review.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine stop   = line(Status.OK, "running run will be asked to stop at its next page", "running runs will be asked to stop at their next page",
         "running run was asked to stop at its next page", "running runs were asked to stop at their next page");
      private final ProcessSummaryLine queued = line(Status.OK, "queued run will be cancelled", "queued runs will be cancelled", "queued run was cancelled", "queued runs were cancelled");
      private final ProcessSummaryLine stale  = line(Status.WARNING, "run has lost its heartbeat (nothing is running it) and will be closed as cancelled",
         "runs have lost their heartbeat (nothing is running them) and will be closed as cancelled", "run had lost its heartbeat and was closed as cancelled",
         "runs had lost their heartbeat and were closed as cancelled");
      private final ProcessSummaryLine no     = line(Status.ERROR, "run cannot be cancelled: it is not running", "runs cannot be cancelled: they are not running",
         "run could not be cancelled: it is not running", "runs could not be cancelled: they are not running");



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord run : input.getRecords())
         {
            switch(actionFor(run))
            {
               case ASK_TO_STOP -> stop.incrementCountAndAddPrimaryKey(run.getValue("id"));
               case CANCEL_NOW -> queued.incrementCountAndAddPrimaryKey(run.getValue("id"));
               case CLOSE_STALE -> stale.incrementCountAndAddPrimaryKey(run.getValue("id"));
               default -> no.incrementCountAndAddPrimaryKey(run.getValue("id"));
            }
            output.getRecords().add(run);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         stop.addSelfToListIfAnyCount(lines);
         queued.addSelfToListIfAnyCount(lines);
         stale.addSelfToListIfAnyCount(lines);
         no.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Result.
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine stop   = line(Status.OK, "running run was asked to stop at its next page", "running runs were asked to stop at their next page",
         "running run was asked to stop at its next page", "running runs were asked to stop at their next page");
      private final ProcessSummaryLine queued = line(Status.OK, "queued run was cancelled", "queued runs were cancelled", "queued run was cancelled", "queued runs were cancelled");
      private final ProcessSummaryLine stale  = line(Status.WARNING, "run that had lost its heartbeat was closed as cancelled", "runs that had lost their heartbeat were closed as cancelled",
         "run that had lost its heartbeat was closed as cancelled", "runs that had lost their heartbeat were closed as cancelled");
      private final ProcessSummaryLine no     = line(Status.ERROR, "run was not cancelled: it is not running", "runs were not cancelled: they are not running",
         "run was not cancelled: it is not running", "runs were not cancelled: they are not running");



      /*******************************************************************************
       ** Cancel each run.
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         BackupCatalog catalog = new BackupCatalog();
         String        table   = input.getTableName();
         for(QRecord selected : input.getRecords())
         {
            QRecord run = catalog.get(table, selected.getValueInteger("id")).orElse(selected);
            switch(actionFor(run))
            {
               case ASK_TO_STOP ->
               {
                  catalog.update(table, run.getValueInteger("id"), Map.of("status", "CANCELLING"));
                  stop.incrementCountAndAddPrimaryKey(run.getValue("id"));
               }
               case CANCEL_NOW ->
               {
                  catalog.update(table, run.getValueInteger("id"), closed("Cancelled before it started."));
                  queued.incrementCountAndAddPrimaryKey(run.getValue("id"));
               }
               case CLOSE_STALE ->
               {
                  catalog.update(table, run.getValueInteger("id"), closed("Cancelled: the run had stopped sending heartbeats."));
                  stale.incrementCountAndAddPrimaryKey(run.getValue("id"));
               }
               default -> no.incrementCountAndAddPrimaryKey(run.getValue("id"));
            }
            output.getRecords().add(run);
         }
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      private static Map<String, Serializable> closed(String message)
      {
         Map<String, Serializable> values = new LinkedHashMap<>();
         values.put("status", "CANCELLED");
         values.put("endDate", BackupsRuntime.getClock().instant());
         values.put("errorMessage", message);
         return (values);
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         stop.addSelfToListIfAnyCount(lines);
         queued.addSelfToListIfAnyCount(lines);
         stale.addSelfToListIfAnyCount(lines);
         no.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }
}
