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


import java.util.Set;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Works out the backup role of a table: the declared role when there is one,
 ** else inferred by the rules of spec 3.2.
 *******************************************************************************/
public final class TableRoles
{
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
