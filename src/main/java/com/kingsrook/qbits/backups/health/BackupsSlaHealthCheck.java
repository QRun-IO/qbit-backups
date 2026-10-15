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


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthDetails;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;


/*******************************************************************************
 ** Read persisted SLA state using the former indicator's critical-plan rule.
 ** SLA calculation and storage verification remain the scheduled checker's job.
 *******************************************************************************/
public class BackupsSlaHealthCheck implements HealthCheck
{
   public static final String NAME = "backups.sla";

   private static final Set<String> BAD_STATUSES = Set.of("LATE", "FAILING", "MISSING_IN_STORAGE");
   private static final List<String> STATUSES = List.of("OK", "LATE", "FAILING", "MISSING_IN_STORAGE", "UNVERIFIED", "ABANDONED", "OWNER_INVALID", "UNKNOWN");



   /*******************************************************************************
    ** Counts use a fixed vocabulary so stored strings cannot become detail keys.
    *******************************************************************************/
   @Override
   public HealthCheckOutcome run(HealthCheckInput input)
   {
      boolean ownsContext = QContext.getQInstance() == null;
      if(ownsContext)
      {
         QContext.init(input.getQInstance(), new QSystemUserSession(input.getQInstance()));
      }
      try
      {
         if(expired(input))
         {
            return (HealthCheckOutcome.down("Backup SLA check timed out"));
         }
         List<QRecord> plans = new BackupCatalog().where(BackupsTables.PLAN, "isActive", true);
         Map<String, Integer> counts = new LinkedHashMap<>();
         STATUSES.forEach(status -> counts.put(status, 0));
         boolean criticalBad = false;
         boolean ordinaryBad = false;
         for(QRecord plan : plans)
         {
            String status = plan.getValueString("slaStatus");
            status = status != null && STATUSES.contains(status) ? status : "UNKNOWN";
            counts.merge(status, 1, Integer::sum);
            if(BAD_STATUSES.contains(status))
            {
               if(Boolean.TRUE.equals(plan.getValueBoolean("critical")))
               {
                  criticalBad = true;
               }
               else
               {
                  ordinaryBad = true;
               }
            }
         }
         if(expired(input))
         {
            return (HealthCheckOutcome.down("Backup SLA check timed out"));
         }
         HealthDetails details = new HealthDetails().withValue("activePlans", plans.size());
         counts.forEach((status, count) -> details.withValue("slaStatus." + status, count));
         HealthCheckOutcome outcome = HealthCheckOutcome.up("Backup SLA has no failures");
         if(criticalBad)
         {
            outcome = HealthCheckOutcome.down("Critical backup plans have SLA failures");
         }
         else if(ordinaryBad)
         {
            outcome = HealthCheckOutcome.degraded("Backup plans have SLA failures");
         }
         else if(!plans.isEmpty() && counts.get("UNVERIFIED") + counts.get("UNKNOWN") == plans.size())
         {
            outcome = HealthCheckOutcome.unknown("Backup SLA is not verified yet");
         }
         return (outcome.withDetails(details));
      }
      catch(QException | RuntimeException e)
      {
         return (HealthCheckOutcome.down("Backup SLA state could not be read"));
      }
      finally
      {
         if(ownsContext)
         {
            QContext.clear();
         }
      }
   }



   /*******************************************************************************
    ** Stop between catalog operations when the runner's budget is exhausted.
    *******************************************************************************/
   static boolean expired(HealthCheckInput input)
   {
      return (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(input.getDeadline()));
   }
}
