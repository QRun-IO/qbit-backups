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
import com.kingsrook.qbits.backups.schedule.BackupPlanSyncToScheduledJobProcess;
import com.kingsrook.qbits.backups.schedule.BackupPlanVerifySyncToScheduledJobProcess;
import com.kingsrook.qbits.backups.schedule.BackupScheduledRunProcess;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** The processes of the run lifecycle (spec 9): scheduled runs, cancel, resume,
 ** test destination and prune, and the plan to scheduled job sync.  Added to the
 ** qbit by BackupsProcesses.processes().  The per-table export process
 ** (exportProcesses) needs the instance tables, so the producer adds it.
 *******************************************************************************/
public final class LifecycleProcesses
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private LifecycleProcesses()
   {
   }



   /*******************************************************************************
    ** The processes.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      processes.add(BackupScheduledRunProcess.processMetaData());
      processes.add(com.kingsrook.qbits.backups.schedule.BackupScheduledVerifyProcess.processMetaData());
      processes.addAll(CancelRunProcess.processes());
      processes.add(ResumeRunProcess.process());
      processes.add(TestDestinationProcess.process());
      processes.addAll(PruneProcess.processes());

      BackupsQBitConfig config = BackupsRuntime.getConfig();
      if(config != null && StringUtils.hasContent(config.getSchedulerName()))
      {
         try
         {
            processes.add(new BackupPlanSyncToScheduledJobProcess().produce(null));
            processes.add(new BackupPlanVerifySyncToScheduledJobProcess().produce(null));
         }
         catch(QException e)
         {
            throw (new IllegalStateException("Could not define the plan schedule sync processes: " + e.getMessage(), e));
         }
      }
      return (processes);
   }
}
