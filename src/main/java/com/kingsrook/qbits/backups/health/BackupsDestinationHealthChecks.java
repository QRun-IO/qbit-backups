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
import java.util.List;
import java.util.Locale;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthCheckSupplier;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSystemUserSession;


/*******************************************************************************
 ** Discover one daily, read-only destination check per active backup plan.
 *******************************************************************************/
public class BackupsDestinationHealthChecks implements HealthCheckSupplier
{
   public static final String PREFIX = "backups.destination";



   /*******************************************************************************
    ** Resolve from current catalog data, without opening any storage connections.
    *******************************************************************************/
   @Override
   public List<HealthCheckMetaData> supply(QInstance qInstance) throws QException
   {
      boolean ownsContext = QContext.getQInstance() == null;
      if(ownsContext)
      {
         QContext.init(qInstance, new QSystemUserSession(qInstance));
      }
      try
      {
         List<HealthCheckMetaData> checks = new ArrayList<>();
         for(QRecord plan : new BackupCatalog().where(BackupsTables.PLAN, "isActive", true))
         {
            Integer planId = plan.getValueInteger("id");
            checks.add(new HealthCheckMetaData().withName(PREFIX + "." + planId.toString().toLowerCase(Locale.ROOT))
               .withLabel("Backup destination " + planId).withComponent("backups")
               .withKind(HealthCheckKind.DEPENDENCY).withCategory(HealthCategory.STORAGE)
               .withIntervalSeconds(86400).withTimeoutMs(30000).withCheckInstance(new BackupsDestinationHealthCheck(planId)));
         }
         return (checks);
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
