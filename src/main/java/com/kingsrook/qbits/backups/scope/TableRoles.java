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

package com.kingsrook.qbits.backups.scope;


import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qqq.backend.core.exceptions.QModuleDispatchException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleInterface;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Works out the backup role of a table: the declared role when there is one,
 ** else inferred by the rules of spec 3.2.
 *******************************************************************************/
public final class TableRoles
{
   private static final QLogger LOG = QLogger.getLogger(TableRoles.class);

   ////////////////////////////////////////////////////////////////////////////////
   // whether a backend type's module has a query interface: the answer depends  //
   // only on the module class, so it is cached per backend type (roleOf is hot) //
   ////////////////////////////////////////////////////////////////////////////////
   private static final Map<String, Boolean> QUERYABLE_BY_BACKEND_TYPE = new ConcurrentHashMap<>();

   private static final Set<String> AUDIT_TABLES = Set.of("audit", "auditTable", "auditUser", "auditDetail");

   private static final String[] SECRET_NAME_HINTS = { "refreshToken", "RefreshToken", "oauthToken", "OAuthToken", "apiKey" };

   private static final String[] EPHEMERAL_NAME_PREFIXES = { "qrtz_" };
   private static final String[] EPHEMERAL_NAME_PARTS    = { "Session", "Idempotency", "RedirectState", "AsyncJob" };



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private TableRoles()
   {
   }



   /*******************************************************************************
    ** The role of a table.
    *******************************************************************************/
   public static TableRole roleOf(QInstance qInstance, QTableMetaData table)
   {
      BackupTableMetaData declared = BackupTableMetaData.of(table);
      if(declared != null && declared.getRole() != null)
      {
         return (declared.getRole());
      }

      String name = table.getName();
      if(name.startsWith(BackupsQBitConfig.TABLE_NAME_PREFIX))
      {
         return (TableRole.BACKUPS);
      }
      for(String hint : SECRET_NAME_HINTS)
      {
         if(name.contains(hint))
         {
            return (TableRole.SECRET);
         }
      }
      if(isHiddenWithSecretFields(table))
      {
         return (TableRole.SECRET);
      }
      if(qInstance != null && !isQueryable(qInstance, table))
      {
         return (TableRole.EXTERNAL);
      }
      if(AUDIT_TABLES.contains(name))
      {
         return (TableRole.AUDIT);
      }
      if(table.getCacheOf() != null)
      {
         return (TableRole.DERIVED);
      }
      for(String prefix : EPHEMERAL_NAME_PREFIXES)
      {
         if(name.startsWith(prefix))
         {
            return (TableRole.EPHEMERAL);
         }
      }
      for(String part : EPHEMERAL_NAME_PARTS)
      {
         if(name.contains(part) || name.toLowerCase().contains(part.toLowerCase()))
         {
            return (TableRole.EPHEMERAL);
         }
      }
      if(qInstance != null && table.getBackendName() != null && qInstance.getBackend(table.getBackendName()) != null
         && "api".equalsIgnoreCase(qInstance.getBackend(table.getBackendName()).getBackendType()))
      {
         return (TableRole.EXTERNAL);
      }
      return (TableRole.DATA);
   }



   /*******************************************************************************
    ** Whether the rows of a table can be read: its backend module must have a query
    ** interface.  A storage-only module (for example qbit-edi's preview download
    ** backend) throws from getQueryInterface(), and reading its table would fail the
    ** whole run, so such a table is never backed up.  A backend whose module cannot
    ** be resolved cannot be read either (not cached: the module may register later).
    *******************************************************************************/
   public static boolean isQueryable(QInstance qInstance, QTableMetaData table)
   {
      if(qInstance == null || table == null || table.getBackendName() == null)
      {
         return (true);
      }
      QBackendMetaData backend = qInstance.getBackend(table.getBackendName());
      if(backend == null || backend.getBackendType() == null)
      {
         return (true);
      }
      Boolean cached = QUERYABLE_BY_BACKEND_TYPE.get(backend.getBackendType());
      if(cached != null)
      {
         return (cached);
      }

      QBackendModuleInterface module;
      try
      {
         module = new QBackendModuleDispatcher().getQBackendModule(backend);
      }
      catch(QModuleDispatchException e)
      {
         LOG.debug("No backend module for a table, so it cannot be backed up", logPair("table", table.getName()), logPair("backendType", backend.getBackendType()));
         return (false);
      }

      boolean queryable;
      try
      {
         queryable = module.getQueryInterface() != null;
      }
      catch(IllegalStateException | UnsupportedOperationException e)
      {
         queryable = false;
      }
      QUERYABLE_BY_BACKEND_TYPE.put(backend.getBackendType(), queryable);
      return (queryable);
   }



   /*******************************************************************************
    ** A table that is hidden and has a password or sensitive field (other than the
    ** keys and dates) is treated as a table of secrets.
    *******************************************************************************/
   static boolean isHiddenWithSecretFields(QTableMetaData table)
   {
      if(!table.getIsHidden())
      {
         return (false);
      }
      for(QFieldMetaData field : table.getFields().values())
      {
         if(field.getType() == QFieldType.PASSWORD || field.getIsSensitive())
         {
            return (true);
         }
      }
      return (false);
   }
}
