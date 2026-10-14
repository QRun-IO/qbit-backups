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

package com.kingsrook.qbits.backups.health;


import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.plan.DestinationProbe;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthDetails;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;


/*******************************************************************************
 ** Reuse the destination diagnostic in read-only mode; never publish its text.
 *******************************************************************************/
public class BackupsDestinationHealthCheck implements HealthCheck
{
   private final Integer planId;



   /*******************************************************************************
    ** Retain only the id; destination and active state are read on each run.
    *******************************************************************************/
   public BackupsDestinationHealthCheck(Integer planId)
   {
      this.planId = planId;
   }



   /*******************************************************************************
    ** A removed or disabled plan can remain registered until supplier refresh.
    *******************************************************************************/
   @Override
   public HealthCheckOutcome run(HealthCheckInput input)
   {
      boolean ownsContext = QContext.getQInstance() == null;
      if(ownsContext)
      {
         QContext.init(input.getQInstance(), new QSession());
      }
      try
      {
         if(BackupsSlaHealthCheck.expired(input))
         {
            return (HealthCheckOutcome.down("Backup destination check timed out"));
         }
         QRecord plan = new BackupCatalog().get(BackupsTables.PLAN, planId).orElse(null);
         if(plan == null || !Boolean.TRUE.equals(plan.getValueBoolean("isActive")))
         {
            return (HealthCheckOutcome.unknown("Backup plan is no longer active"));
         }
         DestinationProbe.Report report = DestinationProbe.probeReadOnly(plan, input.getDeadline());
         if(BackupsSlaHealthCheck.expired(input))
         {
            return (HealthCheckOutcome.down("Backup destination check timed out"));
         }
         return ((report.ok() ? HealthCheckOutcome.up("Backup destination is readable") : HealthCheckOutcome.down("Backup destination probe failed"))
            .withDetails(new HealthDetails().withValue("planId", planId).withValue("readOnly", true)
               .withValue("checks", report.checks().size()).withValue("failedChecks", report.checks().stream().filter(check -> !check.ok()).count())));
      }
      catch(QException | RuntimeException e)
      {
         return (HealthCheckOutcome.down("Backup destination could not be checked"));
      }
      finally
      {
         if(ownsContext)
         {
            QContext.clear();
         }
      }
   }
}
