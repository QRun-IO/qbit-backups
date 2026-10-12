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

package com.kingsrook.qbits.backups.events;


import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qqq.backend.core.logging.QLogger;


/*******************************************************************************
 ** ESB events of the Backups qbit (design spec section 10.4): qqq.backups.run.succeeded,
 ** qqq.backups.run.failed and qqq.backups.restore.completed.  They are for
 ** integrations, not alerting, and are FIRE AND FORGET: a failed publish is logged
 ** at most once and never fails a backup or a restore.
 **
 ** Events go out only when BackupsQBitConfig.esbDestinationName is set and the ESB
 ** (qqq-esb, an optional dependency) is on the class path; otherwise publish() does
 ** nothing.  A test, or an app with its own bus, can bind a publisher with
 ** {@link #setPublisher}.
 *******************************************************************************/
public final class BackupEvents
{
   private static final QLogger LOG = QLogger.getLogger(BackupEvents.class);

   public static final String RUN_SUCCEEDED     = "qqq.backups.run.succeeded";
   public static final String RUN_FAILED        = "qqq.backups.run.failed";
   public static final String RESTORE_COMPLETED = "qqq.backups.restore.completed";

   private static volatile BiConsumer<String, Map<String, Serializable>> publisher;
   private static volatile boolean                                       warned = false;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupEvents()
   {
   }



   /*******************************************************************************
    ** Bind a publisher (event type, data), or null to go back to the ESB default.
    *******************************************************************************/
   public static void setPublisher(BiConsumer<String, Map<String, Serializable>> newPublisher)
   {
      publisher = newPublisher;
      warned = false;
   }



   /*******************************************************************************
    ** A run finished.  Succeeded and warnings publish run.succeeded; failed, timed
    ** out and abandoned publish run.failed; cancelled publishes nothing.
    *******************************************************************************/
   public static void runFinished(String planKey, String runKey, String status, long rows, long bytesStored)
   {
      String type;
      if("SUCCEEDED".equals(status) || "SUCCEEDED_WITH_WARNINGS".equals(status))
      {
         type = RUN_SUCCEEDED;
      }
      else if("CANCELLED".equals(status))
      {
         return;
      }
      else
      {
         type = RUN_FAILED;
      }
      Map<String, Serializable> data = new LinkedHashMap<>();
      data.put("plan", planKey);
      data.put("run", runKey);
      data.put("status", status);
      data.put("rows", rows);
      data.put("bytesStored", bytesStored);
      publish(type, data);
   }



   /*******************************************************************************
    ** A restore finished (any status).
    *******************************************************************************/
   public static void restoreCompleted(String restoreRunKey, String status, long rowsRestored)
   {
      Map<String, Serializable> data = new LinkedHashMap<>();
      data.put("restoreRun", restoreRunKey);
      data.put("status", status);
      data.put("rows", rowsRestored);
      publish(RESTORE_COMPLETED, data);
   }



   /*******************************************************************************
    ** Publish an event.  Never throws.
    *******************************************************************************/
   public static void publish(String type, Map<String, Serializable> data)
   {
      try
      {
         BiConsumer<String, Map<String, Serializable>> bound = publisher;
         if(bound != null)
         {
            bound.accept(type, data);
            return;
         }

         String destination = BackupsRuntime.getConfig() == null ? null : BackupsRuntime.getConfig().getEsbDestinationName();
         if(destination == null)
         {
            return;
         }
         EsbBackupEventPublisher.publish(destination, type, data);
      }
      catch(Exception | LinkageError e)
      {
         if(!warned)
         {
            warned = true;
            LOG.warn("A backup event could not be published (events are best effort)", e);
         }
      }
   }
}
