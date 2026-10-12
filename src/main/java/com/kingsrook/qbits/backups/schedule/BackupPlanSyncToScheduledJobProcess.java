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


import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.scheduledjobs.ScheduledJob;
import com.kingsrook.qqq.backend.core.scheduler.processes.AbstractRecordSyncToScheduledJobProcess;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** Keeps one scheduledJob per active plan in step with the plan cron fields (spec
 ** 9.1), the scheduled reports pattern: foreignKeyType backups_plan, the plan id as
 ** the foreign key value, and a job that runs the scheduled backup entry point
 ** (backupRunScheduledPlan) for that plan.  The plan stays the single editable
 ** source; the job shows up in the standard scheduled jobs screens.
 **
 ** A plan with no cron expression is manual-only and has no job.  A plan that is
 ** made inactive (or loses its cron) has its existing job switched off.  Deleting a
 ** plan removes its job (the table customizer does that).
 *******************************************************************************/
public class BackupPlanSyncToScheduledJobProcess extends AbstractRecordSyncToScheduledJobProcess
{
   public static final String FOREIGN_KEY_TYPE = BackupsTables.PLAN;



   /*******************************************************************************
    ** Whether the plan should have a live job.
    *******************************************************************************/
   protected boolean wantsLiveJob(QRecord plan)
   {
      return (Boolean.TRUE.equals(plan.getValueBoolean("isActive")) && StringUtils.hasContent(cronOf(plan)));
   }



   /*******************************************************************************
    ** The cron expression this job follows.
    *******************************************************************************/
   protected String cronOf(QRecord plan)
   {
      return (plan.getValueString("cronExpression"));
   }



   /*******************************************************************************
    ** Skip plans that need no job; switch off the job of a plan that no longer wants one.
    *******************************************************************************/
   @Override
   public QRecord populateRecordToStore(RunBackendStepInput runBackendStepInput, QRecord destinationRecord, QRecord sourceRecord) throws QException
   {
      boolean exists = destinationRecord != null && destinationRecord.getValue("id") != null;
      if(!wantsLiveJob(sourceRecord))
      {
         if(!exists)
         {
            return (null);
         }
         ScheduledJob off = new ScheduledJob(destinationRecord);
         off.setIsActive(false);
         return (off.toQRecord());
      }
      return (super.populateRecordToStore(runBackendStepInput, destinationRecord, sourceRecord));
   }



   /*******************************************************************************
    ** The cron, the time zone, the scheduler, and a readable label.
    *******************************************************************************/
   @Override
   protected ScheduledJob customizeScheduledJob(ScheduledJob scheduledJob, QRecord sourceRecord) throws QException
   {
      scheduledJob.setCronExpression(cronOf(sourceRecord));
      scheduledJob.setCronTimeZoneId(StringUtils.hasContent(sourceRecord.getValueString("cronTimeZoneId")) ? sourceRecord.getValueString("cronTimeZoneId") : "UTC");
      scheduledJob.setIsActive(true);
      //////////////////////////////////////////////////////////////////////////////////
      // the base class keys the job by its source table; the verify job needs its own //
      //////////////////////////////////////////////////////////////////////////////////
      scheduledJob.setForeignKeyType(getScheduledJobForeignKeyType());
      scheduledJob.setLabel(label(sourceRecord));
      scheduledJob.setDescription(label(sourceRecord) + " (runs the " + getProcessNameScheduledJobParameter() + " process for backup plan " + sourceRecord.getValueInteger("id") + ")");

      BackupsQBitConfig config = BackupsRuntime.getConfig();
      if(!StringUtils.hasContent(scheduledJob.getSchedulerName()) && config != null && StringUtils.hasContent(config.getSchedulerName()))
      {
         scheduledJob.setSchedulerName(config.getSchedulerName());
      }
      return (scheduledJob);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   protected String label(QRecord plan)
   {
      return ("Backup " + plan.getValueString("name"));
   }



   /*******************************************************************************
    ** The process metadata (the scheduled-job sync process).
    *******************************************************************************/
   @Override
   public QProcessMetaData produce(QInstance qInstance) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      QProcessMetaData process = super.produce(qInstance);
      process.setPermissionRules(BackupsTables.permissionRules());
      return (process);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected String getScheduledJobForeignKeyType()
   {
      return (FOREIGN_KEY_TYPE);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected String getRecordForeignKeyFieldName()
   {
      return ("id");
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected String getRecordForeignKeyPossibleValueSourceName()
   {
      return (BackupsTables.PLAN);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected String getSourceTableName()
   {
      return (BackupsTables.PLAN);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected String getProcessNameScheduledJobParameter()
   {
      return (BackupScheduledRunProcess.NAME);
   }
}
