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

package com.kingsrook.qbits.backups.run;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;


/*******************************************************************************
 ** Builds the BackupRunRequest for a plan record: loads the scope rows, resolves
 ** the tables, and picks the storage by the plan bucket alias.
 *******************************************************************************/
public final class BackupRunFactory
{

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupRunFactory()
   {
   }



   /*******************************************************************************
    ** The scope rules of a plan.
    *******************************************************************************/
   public static List<ScopeResolver.ScopeRule> scopeRules(Integer planId) throws QException
   {
      List<ScopeResolver.ScopeRule> rules = new ArrayList<>();
      for(QRecord row : new BackupCatalog().where(BackupsTables.PLAN_SCOPE, "backupPlanId", planId))
      {
         rules.add(new ScopeResolver.ScopeRule("INCLUDE".equals(row.getValueString("action")), row.getValueString("selectorType"), row.getValueString("selectorValue"),
            row.getValueString("filterJson"), !Boolean.FALSE.equals(row.getValueBoolean("includeAssociatedChildren")), row.getValueString("fieldExclusions")));
      }
      return (rules);
   }



   /*******************************************************************************
    ** Resolve the scope of a plan record.
    *******************************************************************************/
   public static ScopeResolver.ResolvedScope resolveScope(QRecord plan) throws QException
   {
      return (new ScopeResolver(QContext.getQInstance()).resolve(PlanSettings.of(plan), scopeRules(plan.getValueInteger("id"))));
   }



   /*******************************************************************************
    ** The request to run a plan.
    *******************************************************************************/
   public static BackupRunRequest forPlan(QRecord plan, String trigger) throws QException
   {
      BackupsQBitConfig config   = BackupsRuntime.getConfig();
      PlanSettings      settings = PlanSettings.of(plan);
      QSession          session  = QContext.getQSession();
      String            user     = session == null || session.getUser() == null ? "system:scheduler" : session.getUser().getIdReference();

      return (new BackupRunRequest()
         .withSettings(settings)
         .withScope(resolveScope(plan))
         .withTrigger(trigger)
         .withRequestedBy("SCHEDULED".equals(trigger) ? "system:scheduler" : user)
         .withSecurityScope("SCHEDULED".equals(trigger) ? "FULL" : "FULL")
         .withBucketAlias(settings.string("storageBucketAlias", null))
         .withStorage(BackupsRuntime.storageFor(settings.string("storageBucketAlias", null)))
         .withSourceResolver(BackupsRuntime::storageFor)
         .withInstanceName(config.getInstanceName() != null ? config.getInstanceName() : "qqq")
         .withPlanName(plan.getValueString("name"))
         .withClock(BackupsRuntime.getClock()));
   }
}
