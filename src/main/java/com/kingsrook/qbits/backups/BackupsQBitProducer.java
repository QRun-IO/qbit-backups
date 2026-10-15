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


import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import com.kingsrook.qbits.backups.health.BackupsDestinationHealthChecks;
import com.kingsrook.qbits.backups.health.BackupsSlaHealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthCheckSupplierMetaData;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.help.bundles.HelpBundles;
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
   public static final String VERSION     = loadVersion();

   private BackupsQBitConfig qBitConfig;
   private String namespace;



   /*******************************************************************************
    ** Read the artifact version from the Maven-filtered resource.
    *******************************************************************************/
   private static String loadVersion()
   {
      try(InputStream inputStream = BackupsQBitProducer.class.getClassLoader().getResourceAsStream("qbit-backups.properties"))
      {
         if(inputStream == null)
         {
            throw (new IllegalStateException("Missing qbit-backups.properties; build with Maven to generate the artifact version"));
         }
         Properties properties = new Properties();
         properties.load(inputStream);
         String version = properties.getProperty("version");
         if(version == null || version.isBlank() || version.contains("${"))
         {
            throw (new IllegalStateException("Missing or unfiltered version in qbit-backups.properties; build with Maven"));
         }
         return (version);
      }
      catch(IOException e)
      {
         throw (new IllegalStateException("Could not read artifact version from qbit-backups.properties", e));
      }
   }



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

      com.kingsrook.qbits.backups.alerts.AlertsQBitAlertSink.bindIfAbsent();
      new com.kingsrook.qbits.backups.alerts.AlertsQBitAlertSink().registerAlertTypes(qInstance);

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
      for(var process : com.kingsrook.qbits.backups.processes.ExportTableProcess.exportProcesses(qInstance))
      {
         process.setSourceQBitName(qBit.getName());
         output.add(process);
      }
      for(var widget : com.kingsrook.qbits.backups.app.BackupsApp.widgets())
      {
         output.add(widget);
      }
      for(var widget : com.kingsrook.qbits.backups.model.BackupsTables.widgets())
      {
         output.add(widget);
      }
      com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData app = com.kingsrook.qbits.backups.app.BackupsApp.app(qBitConfig, tables);
      output.add(app);
      output.addSelfToInstance(qInstance);
      // Registration is metadata-only; core guards startup sync when helpContent is absent.
      HelpBundles.register(qInstance, "help/qbit-backups.yaml");

      HealthChecksMetaData.ofOrWithNew(qInstance)
         .withCheck(new HealthCheckMetaData().withName(BackupsSlaHealthCheck.NAME).withLabel("Backup SLA")
            .withComponent("backups").withKind(HealthCheckKind.DEPENDENCY).withCategory(HealthCategory.STORAGE)
            .withIntervalSeconds(900).withCheck(new QCodeReference(BackupsSlaHealthCheck.class)))
         .withSupplier(new HealthCheckSupplierMetaData().withNamePrefix(BackupsDestinationHealthChecks.PREFIX)
            .withComponent("backups").withSupplier(new QCodeReference(BackupsDestinationHealthChecks.class)));

      // A parent app only lists the apps that are its children, so attach ourselves (for example Admin, then Backups).
      com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData parent = qBitConfig.getParentAppName() == null ? null : qInstance.getApp(qBitConfig.getParentAppName());
      if(parent != null && (parent.getChildren() == null || parent.getChildren().stream().noneMatch(child -> app.getName().equals(child.getName()))))
      {
         parent.withChild(app);
      }
      return (new com.kingsrook.qqq.backend.core.model.metadata.MetaDataProducerMultiOutput());
   }

}
