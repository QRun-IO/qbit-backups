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


import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import com.kingsrook.qbits.backups.codec.ArtifactTransform;
import com.kingsrook.qbits.backups.files.FileReferenceProvider;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qbits.backups.storage.BackupStorage;


/*******************************************************************************
 ** Everything a backup run needs, resolved by the caller (the process step, the
 ** scheduler or a test).
 *******************************************************************************/
public class BackupRunRequest
{
   private PlanSettings               settings;
   private ScopeResolver.ResolvedScope scope;
   private String                     trigger         = "MANUAL";
   private String                     requestedBy     = "system";
   private String                     securityScope   = "FULL";
   private BackupStorage              storage;
   private String                     bucketAlias;
   private String                     instanceName    = "qqq";
   private String                     appVersion;
   private String                     planName;
   private ArtifactTransform          transform       = ArtifactTransform.IDENTITY;
   private String                     encryptionKeyRef;
   private String                     encryptionKeyFingerprint;
   private List<FileReferenceProvider> fileProviders   = new ArrayList<>(List.of(new FileReferenceProvider.FromTableMetaData()));
   private TableReader                reader          = new TableReader.Keyset();
   private Clock                      clock           = Clock.systemUTC();
   private BooleanSupplier            cancelRequested = () -> false;
   private java.util.function.Function<String, BackupStorage> sourceResolver;



   /*******************************************************************************
    ** Getter for settings
    *******************************************************************************/
   public PlanSettings getSettings()
   {
      return (settings);
   }



   /*******************************************************************************
    ** Fluent setter for settings
    *******************************************************************************/
   public BackupRunRequest withSettings(PlanSettings settings)
   {
      this.settings = settings;
      return (this);
   }



   /*******************************************************************************
    ** Getter for scope
    *******************************************************************************/
   public ScopeResolver.ResolvedScope getScope()
   {
      return (scope);
   }



   /*******************************************************************************
    ** Fluent setter for scope
    *******************************************************************************/
   public BackupRunRequest withScope(ScopeResolver.ResolvedScope scope)
   {
      this.scope = scope;
      return (this);
   }



   /*******************************************************************************
    ** Getter for trigger
    *******************************************************************************/
   public String getTrigger()
   {
      return (trigger);
   }



   /*******************************************************************************
    ** Fluent setter for trigger
    *******************************************************************************/
   public BackupRunRequest withTrigger(String trigger)
   {
      this.trigger = trigger;
      return (this);
   }



   /*******************************************************************************
    ** Getter for requestedBy
    *******************************************************************************/
   public String getRequestedBy()
   {
      return (requestedBy);
   }



   /*******************************************************************************
    ** Fluent setter for requestedBy
    *******************************************************************************/
   public BackupRunRequest withRequestedBy(String requestedBy)
   {
      this.requestedBy = requestedBy;
      return (this);
   }



   /*******************************************************************************
    ** Getter for securityScope (FULL, or USER when the run saw only what a user may see)
    *******************************************************************************/
   public String getSecurityScope()
   {
      return (securityScope);
   }



   /*******************************************************************************
    ** Fluent setter for securityScope
    *******************************************************************************/
   public BackupRunRequest withSecurityScope(String securityScope)
   {
      this.securityScope = securityScope;
      return (this);
   }



   /*******************************************************************************
    ** Getter for storage
    *******************************************************************************/
   public BackupStorage getStorage()
   {
      return (storage);
   }



   /*******************************************************************************
    ** Fluent setter for storage
    *******************************************************************************/
   public BackupRunRequest withStorage(BackupStorage storage)
   {
      this.storage = storage;
      return (this);
   }



   /*******************************************************************************
    ** Getter for bucketAlias
    *******************************************************************************/
   public String getBucketAlias()
   {
      return (bucketAlias);
   }



   /*******************************************************************************
    ** Fluent setter for bucketAlias
    *******************************************************************************/
   public BackupRunRequest withBucketAlias(String bucketAlias)
   {
      this.bucketAlias = bucketAlias;
      return (this);
   }



   /*******************************************************************************
    ** Getter for instanceName
    *******************************************************************************/
   public String getInstanceName()
   {
      return (instanceName);
   }



   /*******************************************************************************
    ** Fluent setter for instanceName
    *******************************************************************************/
   public BackupRunRequest withInstanceName(String instanceName)
   {
      this.instanceName = instanceName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for appVersion
    *******************************************************************************/
   public String getAppVersion()
   {
      return (appVersion);
   }



   /*******************************************************************************
    ** Fluent setter for appVersion
    *******************************************************************************/
   public BackupRunRequest withAppVersion(String appVersion)
   {
      this.appVersion = appVersion;
      return (this);
   }



   /*******************************************************************************
    ** Getter for planName
    *******************************************************************************/
   public String getPlanName()
   {
      return (planName);
   }



   /*******************************************************************************
    ** Fluent setter for planName
    *******************************************************************************/
   public BackupRunRequest withPlanName(String planName)
   {
      this.planName = planName;
      return (this);
   }



   /*******************************************************************************
    ** Getter for transform
    *******************************************************************************/
   public ArtifactTransform getTransform()
   {
      return (transform);
   }



   /*******************************************************************************
    ** Set the artifact transform (encryption) with the key reference and fingerprint to record.
    *******************************************************************************/
   public BackupRunRequest withTransform(ArtifactTransform transform, String keyRef, String keyFingerprint)
   {
      this.transform = transform;
      this.encryptionKeyRef = keyRef;
      this.encryptionKeyFingerprint = keyFingerprint;
      return (this);
   }



   /*******************************************************************************
    ** Getter for encryptionKeyRef
    *******************************************************************************/
   public String getEncryptionKeyRef()
   {
      return (encryptionKeyRef);
   }



   /*******************************************************************************
    ** Getter for encryptionKeyFingerprint
    *******************************************************************************/
   public String getEncryptionKeyFingerprint()
   {
      return (encryptionKeyFingerprint);
   }



   /*******************************************************************************
    ** Getter for fileProviders
    *******************************************************************************/
   public List<FileReferenceProvider> getFileProviders()
   {
      return (fileProviders);
   }



   /*******************************************************************************
    ** Add a file reference provider.
    *******************************************************************************/
   public BackupRunRequest withFileProvider(FileReferenceProvider provider)
   {
      this.fileProviders.add(provider);
      return (this);
   }



   /*******************************************************************************
    ** Getter for reader
    *******************************************************************************/
   public TableReader getReader()
   {
      return (reader);
   }



   /*******************************************************************************
    ** Fluent setter for reader
    *******************************************************************************/
   public BackupRunRequest withReader(TableReader reader)
   {
      this.reader = reader;
      return (this);
   }



   /*******************************************************************************
    ** Getter for clock
    *******************************************************************************/
   public Clock getClock()
   {
      return (clock);
   }



   /*******************************************************************************
    ** Fluent setter for clock
    *******************************************************************************/
   public BackupRunRequest withClock(Clock clock)
   {
      this.clock = clock;
      return (this);
   }



   /*******************************************************************************
    ** Getter for cancelRequested
    *******************************************************************************/
   public BooleanSupplier getCancelRequested()
   {
      return (cancelRequested);
   }



   /*******************************************************************************
    ** Fluent setter for cancelRequested
    *******************************************************************************/
   public BackupRunRequest withCancelRequested(BooleanSupplier cancelRequested)
   {
      this.cancelRequested = cancelRequested;
      return (this);
   }



   /*******************************************************************************
    ** Getter for sourceResolver (bucket alias of the referencing files to storage); defaults to the plan storage
    *******************************************************************************/
   public java.util.function.Function<String, BackupStorage> getSourceResolver()
   {
      return (sourceResolver);
   }



   /*******************************************************************************
    ** Fluent setter for sourceResolver
    *******************************************************************************/
   public BackupRunRequest withSourceResolver(java.util.function.Function<String, BackupStorage> sourceResolver)
   {
      this.sourceResolver = sourceResolver;
      return (this);
   }
}
