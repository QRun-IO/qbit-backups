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


import java.util.List;
import java.util.function.Function;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfig;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** Configuration of the Backups qbit.
 **
 ** Storage is resolved through a function from a bucket alias (the value of
 ** backups_plan.storageBucketAlias) to a BackupStorage.  The production resolver
 ** is backed by qbit-object-storage; tests and local development use the
 ** in-memory implementation.  No credential is ever held here or in a table.
 *******************************************************************************/
public class BackupsQBitConfig implements QBitConfig
{
   public static final String TABLE_NAME_PREFIX    = "backups_";
   public static final String PERMISSION_BASE_NAME = "backups";

   private String backendName;
   private String appName  = "backups";
   private String appLabel = "Backups";
   private String parentAppName;
   private String tablePathPrefix;
   private String schedulerName;
   private String instanceName;

   private Integer maxConcurrentRuns       = 1;
   private Integer slaCheckIntervalSeconds = 300;
   private Boolean openWhenAnonymous       = false;
   private Boolean prometheusEndpoint      = false;

   private Function<String, BackupStorage> storageResolver;



   /*******************************************************************************
    ** The backend must be named.
    *******************************************************************************/
   @Override
   public void validate(QInstance qInstance, List<String> errors)
   {
      assertCondition(StringUtils.hasContent(backendName), "backendName is required for the Backups QBit", errors);
   }



   /*******************************************************************************
    ** Tables without their own backend use the configured backend.
    *******************************************************************************/
   @Override
   public String getDefaultBackendNameForTables()
   {
      return (backendName);
   }



   /*******************************************************************************
    ** Getter for backendName
    *******************************************************************************/
   public String getBackendName()
   {
      return (this.backendName);
   }



   /*******************************************************************************
    ** Setter for backendName
    *******************************************************************************/
   public void setBackendName(String backendName)
   {
      this.backendName = backendName;
   }



   /*******************************************************************************
    ** Fluent setter for backendName
    *******************************************************************************/
   public BackupsQBitConfig withBackendName(String backendName)
   {
      this.backendName = backendName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appName
    *******************************************************************************/
   public String getAppName()
   {
      return (appName);
   }



   /*******************************************************************************
    ** Fluent setter for appName
    *******************************************************************************/
   public BackupsQBitConfig withAppName(String appName)
   {
      this.appName = appName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appLabel
    *******************************************************************************/
   public String getAppLabel()
   {
      return (appLabel);
   }



   /*******************************************************************************
    ** Fluent setter for appLabel
    *******************************************************************************/
   public BackupsQBitConfig withAppLabel(String appLabel)
   {
      this.appLabel = appLabel;
      return (this);
   }



   /*******************************************************************************
    ** Getter for parentAppName (for example "admin", so this is Admin, Backups).
    *******************************************************************************/
   public String getParentAppName()
   {
      return (parentAppName);
   }



   /*******************************************************************************
    ** Fluent setter for parentAppName
    *******************************************************************************/
   public BackupsQBitConfig withParentAppName(String parentAppName)
   {
      this.parentAppName = parentAppName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for tablePathPrefix (the front of the links the home makes to tables and
    ** records, "/app" for the Next dashboard, which routes /app/{table}/{id}).
    *******************************************************************************/
   public String getTablePathPrefix()
   {
      return (tablePathPrefix == null ? "/app" : tablePathPrefix);
   }



   /*******************************************************************************
    ** Fluent setter for tablePathPrefix
    *******************************************************************************/
   public BackupsQBitConfig withTablePathPrefix(String tablePathPrefix)
   {
      this.tablePathPrefix = tablePathPrefix;
      return (this);
   }



   /*******************************************************************************
    ** Getter for schedulerName (the QQQ scheduler used for plan jobs and the SLA checker)
    *******************************************************************************/
   public String getSchedulerName()
   {
      return (schedulerName);
   }



   /*******************************************************************************
    ** Fluent setter for schedulerName
    *******************************************************************************/
   public BackupsQBitConfig withSchedulerName(String schedulerName)
   {
      this.schedulerName = schedulerName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for instanceName (used in object keys); defaults to the QInstance name
    *******************************************************************************/
   public String getInstanceName()
   {
      return (instanceName);
   }



   /*******************************************************************************
    ** Fluent setter for instanceName
    *******************************************************************************/
   public BackupsQBitConfig withInstanceName(String instanceName)
   {
      this.instanceName = instanceName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for maxConcurrentRuns
    *******************************************************************************/
   public Integer getMaxConcurrentRuns()
   {
      return (maxConcurrentRuns);
   }



   /*******************************************************************************
    ** Fluent setter for maxConcurrentRuns
    *******************************************************************************/
   public BackupsQBitConfig withMaxConcurrentRuns(Integer maxConcurrentRuns)
   {
      this.maxConcurrentRuns = maxConcurrentRuns;
      return (this);
   }



   /*******************************************************************************
    ** Getter for slaCheckIntervalSeconds
    *******************************************************************************/
   public Integer getSlaCheckIntervalSeconds()
   {
      return (slaCheckIntervalSeconds);
   }



   /*******************************************************************************
    ** Fluent setter for slaCheckIntervalSeconds
    *******************************************************************************/
   public BackupsQBitConfig withSlaCheckIntervalSeconds(Integer slaCheckIntervalSeconds)
   {
      this.slaCheckIntervalSeconds = slaCheckIntervalSeconds;
      return (this);
   }



   /*******************************************************************************
    ** Getter for openWhenAnonymous (local development only)
    *******************************************************************************/
   public Boolean getOpenWhenAnonymous()
   {
      return (openWhenAnonymous);
   }



   /*******************************************************************************
    ** Fluent setter for openWhenAnonymous
    *******************************************************************************/
   public BackupsQBitConfig withOpenWhenAnonymous(Boolean openWhenAnonymous)
   {
      this.openWhenAnonymous = openWhenAnonymous;
      return (this);
   }



   /*******************************************************************************
    ** Getter for prometheusEndpoint
    *******************************************************************************/
   public Boolean getPrometheusEndpoint()
   {
      return (prometheusEndpoint);
   }



   /*******************************************************************************
    ** Fluent setter for prometheusEndpoint
    *******************************************************************************/
   public BackupsQBitConfig withPrometheusEndpoint(Boolean prometheusEndpoint)
   {
      this.prometheusEndpoint = prometheusEndpoint;
      return (this);
   }



   /*******************************************************************************
    ** Getter for storageResolver
    *******************************************************************************/
   public Function<String, BackupStorage> getStorageResolver()
   {
      return (storageResolver);
   }



   /*******************************************************************************
    ** Fluent setter for storageResolver: bucket alias to storage
    *******************************************************************************/
   public BackupsQBitConfig withStorageResolver(Function<String, BackupStorage> storageResolver)
   {
      this.storageResolver = storageResolver;
      return (this);
   }

}
