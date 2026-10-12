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
import java.lang.reflect.Method;
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
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.ActionFlag;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleInterface;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The default RestoreWriter: the standard Insert, Update and Delete actions.
 **
 ** Stand-ins (replace with the core API when QQQ 4.1.0-RC.5 ships):
 ** <ul>
 ** <li>RestoreWriteFlag (QRun-IO/qqq#1111) is created reflectively when the class is
 ** present.  Without it, the DML audit is omitted (a summary audit is written per
 ** table instead), but created and modified dates may be set by the normal defaults.</li>
 ** <li>resyncKeySequences (QRun-IO/qqq#1112) is called reflectively on the backend
 ** module when it exists; otherwise it is a no-op that the Review reports.</li>
 ** </ul>
 *******************************************************************************/
public class StandardRestoreWriter implements RestoreWriter
{
   private static final QLogger LOG = QLogger.getLogger(StandardRestoreWriter.class);

   private static final String FLAG_CLASS = "com.kingsrook.qqq.backend.core.model.actions.tables.RestoreWriteFlag";

   private static final Class<?> FLAG = lookup(FLAG_CLASS);



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Class<?> lookup(String name)
   {
      try
      {
         return (Class.forName(name));
      }
      catch(ClassNotFoundException | LinkageError e)
      {
         return (null);
      }
   }



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
    ** Create the core flag (when the running QQQ has it) and hand it to the input.
    *******************************************************************************/
   private void addFlag(RestoreRequest options, java.util.function.Consumer<ActionFlag> sink) throws QException
   {
      if(FLAG == null)
      {
         return;
      }
      try
      {
         Object flag = FLAG.getDeclaredConstructor().newInstance();
         FLAG.getMethod("withSuppressAutomations", boolean.class).invoke(flag, options.getSuppressAutomations());
         FLAG.getMethod("withRunCustomizers", boolean.class).invoke(flag, options.getRunCustomizers());
         FLAG.getMethod("withPerRecordAudit", boolean.class).invoke(flag, options.getPerRecordAudit());
         FLAG.getMethod("withPublishEvents", boolean.class).invoke(flag, options.getPublishEvents());
         sink.accept((ActionFlag) flag);
      }
      catch(ReflectiveOperationException | ClassCastException e)
      {
         throw (new QException("Could not use the core restore flag: " + e.getMessage(), e));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public boolean usesCoreRestoreFlag()
   {
      return (FLAG != null);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public boolean canResyncSequences()
   {
      return (resyncMethod() != null);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Method resyncMethod()
   {
      try
      {
         return (QBackendModuleInterface.class.getMethod("resyncKeySequences", QBackendMetaData.class, Collection.class));
      }
      catch(NoSuchMethodException | LinkageError e)
      {
         return (null);
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<String> resyncSequences(Collection<QTableMetaData> tables) throws QException
   {
      Method method = resyncMethod();
      if(method == null)
      {
         return (List.of());
      }

      Map<String, List<QTableMetaData>> byBackend = new LinkedHashMap<>();
      for(QTableMetaData table : tables)
      {
         byBackend.computeIfAbsent(table.getBackendName(), k -> new ArrayList<>()).add(table);
      }

      List<String> resynced = new ArrayList<>();
      for(Map.Entry<String, List<QTableMetaData>> entry : byBackend.entrySet())
      {
         QBackendMetaData backend = QContext.getQInstance().getBackend(entry.getKey());
         try
         {
            QBackendModuleInterface module = new QBackendModuleDispatcher().getQBackendModule(backend);
            for(Object result : (List<?>) method.invoke(module, backend, entry.getValue()))
            {
               Object status = result.getClass().getMethod("getStatus").invoke(result);
               if("RESYNCED".equals(String.valueOf(status)))
               {
                  resynced.add(String.valueOf(result.getClass().getMethod("getTableName").invoke(result)));
               }
            }
         }
         catch(ReflectiveOperationException e)
         {
            LOG.warn("Could not resync key sequences", e, logPair("backend", entry.getKey()));
            throw (new QException("Could not resync key sequences on backend " + entry.getKey() + ": " + e.getMessage(), e));
         }
      }
      return (resynced);
   }
}
