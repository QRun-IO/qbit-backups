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

package com.kingsrook.qbits.backups.restore;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.ActionFlag;
import com.kingsrook.qqq.backend.core.model.actions.tables.RestoreWriteFlag;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleInterface;
import com.kingsrook.qqq.backend.core.modules.backend.SequenceResyncResult;


/*******************************************************************************
 ** The default RestoreWriter using core restore flags and key sequence resync.
 *******************************************************************************/
public class StandardRestoreWriter implements RestoreWriter
{
   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<QRecord> insert(QTableMetaData table, List<QRecord> records, RestoreRequest options, QBackendTransaction transaction) throws QException
   {
      InsertInput input = new InsertInput(table.getName());
      input.setRecords(records);
      input.setOmitDmlAudit(!options.getPerRecordAudit());
      if(transaction != null)
      {
         input.setTransaction(transaction);
      }
      addFlag(options, input::withFlag);
      return (new InsertAction().execute(input).getRecords());
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<QRecord> update(QTableMetaData table, List<QRecord> records, RestoreRequest options, QBackendTransaction transaction) throws QException
   {
      UpdateInput input = new UpdateInput(table.getName());
      input.setRecords(records);
      input.setOmitDmlAudit(!options.getPerRecordAudit());
      if(transaction != null)
      {
         input.setTransaction(transaction);
      }
      addFlag(options, input::withFlag);
      return (new UpdateAction().execute(input).getRecords());
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public long delete(QTableMetaData table, List<Serializable> primaryKeys, RestoreRequest options, QBackendTransaction transaction) throws QException
   {
      DeleteInput input = new DeleteInput(table.getName());
      input.setPrimaryKeys(primaryKeys);
      input.setOmitDmlAudit(!options.getPerRecordAudit());
      if(transaction != null)
      {
         input.setTransaction(transaction);
      }
      addFlag(options, input::withFlag);
      DeleteOutput output = new DeleteAction().execute(input);
      if(output.getRecordsWithErrors() != null && !output.getRecordsWithErrors().isEmpty())
      {
         QRecord first = output.getRecordsWithErrors().get(0);
         throw (new QException("Could not delete " + output.getRecordsWithErrors().size() + " records of " + table.getName() + ": "
            + (first.getErrors().isEmpty() ? "unknown error" : first.getErrors().get(0).getMessage())));
      }
      return (output.getDeletedRecordCount());
   }



   /*******************************************************************************
    ** Apply the core restore behavior requested by the caller.
    *******************************************************************************/
   private void addFlag(RestoreRequest options, java.util.function.Consumer<ActionFlag> sink)
   {
      sink.accept(new RestoreWriteFlag()
         .withSuppressAutomations(options.getSuppressAutomations())
         .withRunCustomizers(options.getRunCustomizers())
         .withPerRecordAudit(options.getPerRecordAudit())
         .withPublishEvents(options.getPublishEvents()));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public boolean usesCoreRestoreFlag()
   {
      return (true);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public boolean canResyncSequences()
   {
      return (true);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<String> resyncSequences(Collection<QTableMetaData> tables) throws QException
   {
      Map<String, List<QTableMetaData>> byBackend = new LinkedHashMap<>();
      for(QTableMetaData table : tables)
      {
         byBackend.computeIfAbsent(table.getBackendName(), k -> new ArrayList<>()).add(table);
      }

      List<String> resynced = new ArrayList<>();
      for(Map.Entry<String, List<QTableMetaData>> entry : byBackend.entrySet())
      {
         QBackendMetaData backend = QContext.getQInstance().getBackend(entry.getKey());
         QBackendModuleInterface module = new QBackendModuleDispatcher().getQBackendModule(backend);
         for(SequenceResyncResult result : module.resyncKeySequences(backend, entry.getValue()))
         {
            if(result.getStatus() == SequenceResyncResult.Status.RESYNCED)
            {
               resynced.add(result.getTableName());
            }
         }
      }
      return (resynced);
   }
}
