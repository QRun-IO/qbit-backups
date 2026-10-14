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


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;
import com.kingsrook.qqq.middleware.health.HealthIndicator;
import com.kingsrook.qqq.middleware.health.model.metadata.HealthCheckResult;
import com.kingsrook.qqq.middleware.health.model.metadata.HealthStatus;


/*******************************************************************************
 ** The Backups health indicator (design spec section 10.4), for
 ** qqq-middleware-health (an optional dependency: an app that does not have that
 ** module never loads this class).  It reports DOWN if any active plan marked
 ** critical is LATE, FAILING or MISSING_IN_STORAGE, and UP with details otherwise.
 ** It reads the slaStatus the SLA checker maintains; it never touches storage, so
 ** a health call is cheap.
 **
 ** It is OFF the liveness path by design.  Add it to the indicators of the
 ** instance's health check (the readiness and monitoring view), and do not point a
 ** Kubernetes liveness probe at a check that includes it: a backup problem must
 ** never restart pods.
 *******************************************************************************/
public class BackupsHealthIndicator implements HealthIndicator
{
   public static final String NAME = "backups";

   private static final Set<String> CRITICAL_STATUSES = Set.of("LATE", "FAILING", "MISSING_IN_STORAGE");



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String getName()
   {
      return (NAME);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public HealthCheckResult check(QInstance qInstance) throws QException
   {
      long                started = System.currentTimeMillis();
      boolean             ownsContext = QContext.getQInstance() == null;
      if(ownsContext)
      {
         QContext.init(qInstance, systemSession(qInstance));
      }
      try
      {
         List<QRecord> plans = new BackupCatalog().query(BackupsTables.PLAN, new QQueryFilter(new QFilterCriteria("isActive", QCriteriaOperator.EQUALS, true)));

         List<String> criticalProblems = new ArrayList<>();
         Map<String, Integer> byStatus = new LinkedHashMap<>();
         for(QRecord plan : plans)
         {
            String status = plan.getValueString("slaStatus") == null ? "UNKNOWN" : plan.getValueString("slaStatus");
            byStatus.merge(status, 1, Integer::sum);
            if(Boolean.TRUE.equals(plan.getValueBoolean("critical")) && CRITICAL_STATUSES.contains(status))
            {
               criticalProblems.add(plan.getValueString("planKey") + ": " + status);
            }
         }

         Map<String, Object> details = new LinkedHashMap<>();
         details.put("activePlans", plans.size());
         details.put("plansByStatus", byStatus);
         if(!criticalProblems.isEmpty())
         {
            details.put("criticalPlansWithProblems", criticalProblems);
         }
         return (new HealthCheckResult()
            .withStatus(criticalProblems.isEmpty() ? HealthStatus.UP : HealthStatus.DOWN)
            .withDurationMs(System.currentTimeMillis() - started)
            .withDetails(details));
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
    ** The session for a health request that has no QQQ context. It must be the
    ** core system user: token-based authentication modules (OAuth2, Auth0)
    ** reject any other tokenless session, which failed the check on such hosts.
    *******************************************************************************/
   static QSession systemSession(QInstance qInstance)
   {
      return (new QSystemUserSession(qInstance));
   }
}
