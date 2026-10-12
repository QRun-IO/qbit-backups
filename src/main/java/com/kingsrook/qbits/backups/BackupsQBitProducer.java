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

package com.kingsrook.qbits.backups;


import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitMetaDataProducer;


/*******************************************************************************
 ** Produces the Backups QBit. Component producers in this package are found by
 ** package scanning.
 *******************************************************************************/
public class BackupsQBitProducer implements QBitMetaDataProducer<BackupsQBitConfig>
{
   public static final String GROUP_ID    = "com.kingsrook.qbits";
   public static final String ARTIFACT_ID = "qbit-backups";
   public static final String VERSION     = "0.1.0";

   private BackupsQBitConfig qBitConfig;
   private String namespace;



   /*******************************************************************************
    ** Fluent setter for the config.
    *******************************************************************************/
   public BackupsQBitProducer withQBitConfig(BackupsQBitConfig config)
   {
      this.qBitConfig = config;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the config.
    *******************************************************************************/
   @Override
   public BackupsQBitConfig getQBitConfig()
   {
      return (qBitConfig);
   }



   /*******************************************************************************
    ** Fluent setter for the namespace.
    *******************************************************************************/
   public BackupsQBitProducer withNamespace(String namespace)
   {
      this.namespace = namespace;
      return (this);
   }



   /*******************************************************************************
    ** Getter for the namespace.
    *******************************************************************************/
   @Override
   public String getNamespace()
   {
      return (namespace);
   }



   /*******************************************************************************
    ** The qbit's identity.
    *******************************************************************************/
   @Override
   public QBitMetaData getQBitMetaData()
   {
      return (new QBitMetaData().withGroupId(GROUP_ID).withArtifactId(ARTIFACT_ID).withVersion(VERSION).withNamespace(namespace).withConfig(qBitConfig));
   }



   /*******************************************************************************
    ** Producers found by package scanning have no config: skip them.
    *******************************************************************************/
   @Override
   public boolean isEnabled()
   {
      return (qBitConfig != null);
   }



   /*******************************************************************************
    ** Build everything and add it to the instance: possible-value sources, joins,
    ** tables, processes, widgets and the app.
    *******************************************************************************/
   @Override
   public com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput produce(com.kingsrook.qqq.backend.core.model.metadata.QInstance qInstance) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      java.util.List<String> errors = new java.util.ArrayList<>();
      qBitConfig.validate(qInstance, errors);
      if(!errors.isEmpty())
      {
         throw (new com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfigValidationException(qBitConfig, errors));
      }
      QBitMetaData qBit = getQBitMetaData();
      qInstance.addQBit(qBit);
      BackupsRuntime.install(qBitConfig);

      ///////////////////////////////////////////////////////////////////////////////
      // bind the Alerts qbit producer SPI when it is on the class path (optional) //
      ///////////////////////////////////////////////////////////////////////////////
      if(com.kingsrook.qbits.backups.alerts.AlertsQBitAlertSink.bindIfPresent())
      {
         new com.kingsrook.qbits.backups.alerts.AlertsQBitAlertSink().registerAlertTypes(qInstance);
      }

      com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput output = new com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput();
      for(var possibleValueSource : com.kingsrook.qbits.backups.model.BackupsTables.possibleValueSources())
      {
         output.add(possibleValueSource);
      }
      for(var join : com.kingsrook.qbits.backups.model.BackupsTables.joins())
      {
         output.add(join);
      }
      java.util.List<com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData> tables = new java.util.ArrayList<>();
      for(var table : com.kingsrook.qbits.backups.model.BackupsTables.tables(qBitConfig))
      {
         var customized = qBitConfig.getTableMetaDataCustomizer() == null ? table : qBitConfig.getTableMetaDataCustomizer().customizeMetaData(qInstance, table);
         customized.setSourceQBitName(qBit.getName());
         tables.add(customized);
         output.add(customized);
      }
      for(var process : com.kingsrook.qbits.backups.processes.BackupsProcesses.processes())
      {
         process.setSourceQBitName(qBit.getName());
         output.add(process);
      }
      for(var widget : com.kingsrook.qbits.backups.app.BackupsApp.widgets())
      {
         output.add(widget);
      }
      output.add(com.kingsrook.qbits.backups.app.BackupsApp.app(qBitConfig, tables));
      output.addSelfToInstance(qInstance);
      return (new com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput());
   }

}
