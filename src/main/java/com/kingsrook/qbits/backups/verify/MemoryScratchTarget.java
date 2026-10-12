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

package com.kingsrook.qbits.backups.verify;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;


/*******************************************************************************
 ** The MEMORY scratch target (design spec section 10.1): QQQ's memory backend.  It
 ** proves the files and the codecs, not the database.  Each source table is cloned
 ** into a temporary in-memory QTableMetaData (a different name, the memory backend,
 ** no customizers, automation, behaviors, unique keys or associations) that is
 ** registered in the instance for the length of the verification and removed by
 ** drop().
 *******************************************************************************/
public class MemoryScratchTarget implements ScratchTarget
{
   public static final String BACKEND_NAME = "qqq_verify_memory";
   public static final String TYPE         = "MEMORY";

   private final Map<String, List<String>> tablesByRun = new LinkedHashMap<>();



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String type()
   {
      return (TYPE);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String describe(String runKey)
   {
      return ("memory:" + prefix(runKey));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public synchronized ScratchTable create(QTableMetaData source, String runKey) throws QException
   {
      QInstance qInstance = QContext.getQInstance();
      if(qInstance.getBackend(BACKEND_NAME) == null)
      {
         qInstance.addBackend(new QBackendMetaData().withName(BACKEND_NAME).withBackendType(MemoryBackendModule.class));
      }

      String         scratchName = prefix(runKey) + "_" + source.getName();
      QTableMetaData clone       = source.clone();
      clone.setName(scratchName);
      clone.setBackendName(BACKEND_NAME);
      clone.setBackendDetails(null);
      clone.setAutomationDetails(null);
      clone.setCustomizers(null);
      clone.setUniqueKeys(null);
      clone.setAssociations(new ArrayList<>());
      clone.setRecordSecurityLocks(new ArrayList<>());
      clone.setExposedJoins(new ArrayList<>());
      clone.setSupplementalMetaData(null);
      clone.setSourceQBitName(null);
      clone.getFields().values().forEach(field -> field.setBehaviors(null));
      qInstance.getTables().put(scratchName, clone);
      tablesByRun.computeIfAbsent(runKey, k -> new ArrayList<>()).add(scratchName);

      return (new ScratchTable()
      {
         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public void insert(List<QRecord> records) throws QException
         {
            InsertInput input = new InsertInput(scratchName);
            input.setRecords(records);
            input.withPermissionBypass("backups verify scratch");
            List<QRecord> inserted = new InsertAction().execute(input).getRecords();
            for(QRecord record : inserted)
            {
               if(record.getErrors() != null && !record.getErrors().isEmpty())
               {
                  throw (new QException("A row could not be restored into the scratch table for " + source.getName() + ": " + record.getErrors().get(0).getMessage()));
               }
            }
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public void readInPrimaryKeyOrder(int pageSize, Consumer<QRecord> consumer) throws QException
         {
            int skip = 0;
            while(true)
            {
               QueryInput input = new QueryInput(scratchName);
               input.setFilter(new QQueryFilter().withOrderBy(new QFilterOrderBy(source.getPrimaryKeyField(), true)).withSkip(skip).withLimit(pageSize));
               input.withPermissionBypass("backups verify scratch");
               List<QRecord> page = new QueryAction().execute(input).getRecords();
               page.forEach(consumer);
               if(page.size() < pageSize)
               {
                  return;
               }
               skip += pageSize;
            }
         }
      });
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public synchronized void drop(String runKey) throws QException
   {
      List<String> names = tablesByRun.remove(runKey);
      if(names == null)
      {
         return;
      }
      for(String name : names)
      {
         DeleteInput input = new DeleteInput(name);
         input.setQueryFilter(new QQueryFilter());
         input.withPermissionBypass("backups verify scratch");
         new DeleteAction().execute(input);
         QContext.getQInstance().getTables().remove(name);
      }
   }



   /*******************************************************************************
    ** The scratch name prefix of a run: qqq_verify_ plus the run key made safe.
    *******************************************************************************/
   static String prefix(String runKey)
   {
      return ("qqq_verify_" + runKey.replaceAll("[^A-Za-z0-9]", "_").toLowerCase());
   }
}
