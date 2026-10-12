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


import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The second scheduled job of a plan (spec 9.1): the one that follows
 ** verifyCronExpression and runs the scheduled verification of the plan's newest
 ** successful run.  It has its own foreign key type (backups_plan_verify) so the
 ** two jobs of one plan do not collide.
 **
 ** The process it runs, backupVerifyPlan, is registered by the verify work (spec
 ** 10.1); this class only owns the schedule.
 *******************************************************************************/
public class BackupPlanVerifySyncToScheduledJobProcess extends BackupPlanSyncToScheduledJobProcess
{
   public static final String FOREIGN_KEY_TYPE      = BackupsTables.PLAN + "_verify";
   public static final String VERIFY_PROCESS_NAME   = "backupVerifyPlan";



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected String cronOf(QRecord plan)
   {
      return (plan.getValueString("verifyCronExpression"));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected String label(QRecord plan)
   {
      return ("Verify backup " + plan.getValueString("name"));
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
   protected String getProcessNameScheduledJobParameter()
   {
      return (VERIFY_PROCESS_NAME);
   }
}
