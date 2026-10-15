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


import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.alerts.NoOpBackupAlertSink;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.BackupStorageException;
import com.kingsrook.qqq.backend.core.cluster.ClusterCaches;


/*******************************************************************************
 ** Process-wide state of the installed qbit: the config, the clock, the alert
 ** sink and the per-process permission map.  Static because QQQ instantiates
 ** steps and permission checkers by class reference.
 *******************************************************************************/
public final class BackupsRuntime
{
   private static final Map<String, String> PROCESS_PERMISSIONS = ClusterCaches.nodeLocal(new ConcurrentHashMap<>(),
      "built from the qbit config at startup, the same on every node");

   private static volatile BackupsQBitConfig config;
   private static volatile Clock             clock           = Clock.systemUTC();
   private static volatile BackupAlertSink   alertSink       = new NoOpBackupAlertSink();
   private static volatile Function<String, String> environment = System::getenv;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsRuntime()
   {
   }



   /*******************************************************************************
    ** Install the config (called by the producer).
    *******************************************************************************/
   public static void install(BackupsQBitConfig newConfig)
   {
      config = newConfig;
   }



   /*******************************************************************************
    ** Forget everything (tests).
    *******************************************************************************/
   public static void reset()
   {
      config = null;
      clock = Clock.systemUTC();
      alertSink = new NoOpBackupAlertSink();
      environment = System::getenv;
      PROCESS_PERMISSIONS.clear();
   }



   /*******************************************************************************
    ** The installed config, or null.
    *******************************************************************************/
   public static BackupsQBitConfig getConfig()
   {
      return (config);
   }



   /*******************************************************************************
    ** Whether the qbit is installed.
    *******************************************************************************/
   public static boolean isInstalled()
   {
      return (config != null);
   }



   /*******************************************************************************
    ** Whether permissions are open on fully anonymous instances (local development).
    *******************************************************************************/
   public static boolean isOpenWhenAnonymous()
   {
      return (config != null && Boolean.TRUE.equals(config.getOpenWhenAnonymous()));
   }



   /*******************************************************************************
    ** The clock (replaceable in tests).
    *******************************************************************************/
   public static Clock getClock()
   {
      return (clock);
   }



   /*******************************************************************************
    ** Replace the clock.
    *******************************************************************************/
   public static void setClock(Clock newClock)
   {
      clock = newClock;
   }



   /*******************************************************************************
    ** The alert sink.
    *******************************************************************************/
   public static BackupAlertSink getAlertSink()
   {
      return (alertSink);
   }



   /*******************************************************************************
    ** Bind an alert sink (the Alerts qbit adapter, or a test double).
    *******************************************************************************/
   public static void setAlertSink(BackupAlertSink newSink)
   {
      alertSink = newSink == null ? new NoOpBackupAlertSink() : newSink;
   }



   /*******************************************************************************
    ** Environment lookup (replaceable in tests).
    *******************************************************************************/
   public static Function<String, String> getEnvironment()
   {
      return (environment);
   }



   /*******************************************************************************
    ** Replace the environment lookup.
    *******************************************************************************/
   public static void setEnvironment(Function<String, String> newEnvironment)
   {
      environment = newEnvironment;
   }



   /*******************************************************************************
    ** The permission each qbit process needs, by process name.
    *******************************************************************************/
   public static Map<String, String> processPermissions()
   {
      return (PROCESS_PERMISSIONS);
   }



   /*******************************************************************************
    ** Resolve the storage for a bucket alias.
    *******************************************************************************/
   public static BackupStorage storageFor(String bucketAlias)
   {
      if(config == null || config.getStorageResolver() == null)
      {
         throw (new BackupStorageException(BackupStorageException.Reason.UNSUPPORTED, "No storage resolver is configured for the Backups qbit"));
      }
      BackupStorage storage = config.getStorageResolver().apply(bucketAlias);
      if(storage == null)
      {
         throw (new BackupStorageException(BackupStorageException.Reason.NOT_FOUND, "No storage bucket is configured with the alias: " + bucketAlias));
      }
      return (storage);
   }
}
