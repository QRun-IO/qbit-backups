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
import java.util.List;
import java.util.function.Consumer;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;


/*******************************************************************************
 ** Reads the rows of one table in primary key order, in keyset pages (WHERE pk >
 ** lastKey ORDER BY pk LIMIT n), so the cost stays flat with depth and a read can
 ** resume from a key.  A read raw: hidden fields, passwords and heavy fields are
 ** returned as stored, and possible values are not translated.
 **
 ** This is an internal stand-in for the QQQ core KeysetPager and QueryInput
 ** withRawRead (core PR feature/keyset-pager-raw-read); the run engine only
 ** depends on this interface, so it is swapped when that ships in the next RC.
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
         String       pk      = table.getPrimaryKeyField();
         Serializable lastKey = startAfterKey;
         QQueryFilter user    = parseFilter(filterJson);

         while(true)
         {
            QQueryFilter filter = new QQueryFilter();
            if(lastKey != null)
            {
               filter.addCriteria(new QFilterCriteria(pk, QCriteriaOperator.GREATER_THAN, List.of(lastKey)));
            }
            if(user != null)
            {
               filter.addSubFilter(user);
            }
            filter.withOrderBy(new QFilterOrderBy(pk, true));
            filter.setLimit(pageSize);

            QueryInput queryInput = new QueryInput(table.getName());
            queryInput.setFilter(filter);
            queryInput.setShouldOmitHiddenFields(false);
            queryInput.setShouldMaskPasswords(false);
            queryInput.setShouldFetchHeavyFields(true);
            queryInput.setShouldTranslatePossibleValues(false);
            queryInput.setShouldGenerateDisplayValues(false);
            if(transaction != null)
            {
               queryInput.setTransaction(transaction);
            }
            queryInput.withPermissionBypass("backups run");

            List<QRecord> page = new QueryAction().execute(queryInput).getRecords();
            for(QRecord record : page)
            {
               sink.accept(record);
               lastKey = record.getValue(pk);
            }
            if(page.size() < pageSize)
            {
               return;
            }
         }
      }
   }
}
