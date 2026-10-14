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


import java.io.Serializable;
import java.util.function.Consumer;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.KeysetPager;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;


/*******************************************************************************
 ** Reads rows in primary key order through core KeysetPager. Each bounded page
 ** uses the supplied transaction and returns hidden, password and heavy fields
 ** as stored, without possible-value translation or display generation.
 *******************************************************************************/
public interface TableReader
{

   /***************************************************************************
    ** Read every row of the table matching the optional filter, after startAfterKey
    ** (exclusive, null for the beginning), calling the sink for each in key order.
    ***************************************************************************/
   void read(QTableMetaData table, String filterJson, Serializable startAfterKey, int pageSize, QBackendTransaction transaction, Consumer<QRecord> sink) throws QException;


   /***************************************************************************
    ** The default implementation over QueryAction.
    ***************************************************************************/
   class Keyset implements TableReader
   {
      /*******************************************************************************
       **
       *******************************************************************************/
      private static QQueryFilter parseFilter(String filterJson) throws QException
      {
         try
         {
            return (filterJson == null || filterJson.isBlank() ? null : JsonUtils.toObject(filterJson, QQueryFilter.class));
         }
         catch(java.io.IOException e)
         {
            throw (new QException("Could not read a scope filter: " + e.getMessage()));
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void read(QTableMetaData table, String filterJson, Serializable startAfterKey, int pageSize, QBackendTransaction transaction, Consumer<QRecord> sink) throws QException
      {
         KeysetPager pager = KeysetPager.forTable(table.getName())
            .withFilter(parseFilter(filterJson))
            .startingAfter(startAfterKey)
            .withPageSize(pageSize)
            .withTransaction(transaction)
            .withQueryInputCustomizer(input ->
            {
               input.setShouldOmitHiddenFields(false);
               input.setShouldMaskPasswords(false);
               input.setShouldFetchHeavyFields(true);
               input.setShouldTranslatePossibleValues(false);
               input.setShouldGenerateDisplayValues(false);
               input.withPermissionBypass("backups run");
            });
         if(!pager.isPrimaryKeyOrderable())
         {
            throw (new QException("Backup requires an orderable primary key on table " + table.getName()));
         }
         pager.forEachPage(page -> page.forEach(sink));
      }
   }
}
