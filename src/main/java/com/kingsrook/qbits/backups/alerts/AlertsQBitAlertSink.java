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

package com.kingsrook.qbits.backups.alerts;


import java.util.Map;
import java.util.stream.Collectors;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qqq.backend.core.alerts.AlertSeverity;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlertTypeMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;


/*******************************************************************************
 ** Direct adapter to core QAlerts. Calls are no-ops without a configured alerts
 ** sink, including when only the backup alert types have been registered.
 ** QAlerts catches and logs dispatch failures; adapter failures are logged once.
 ** Heartbeat intervals remain the responsibility of the backup SLA checker.
 *******************************************************************************/
public class AlertsQBitAlertSink implements BackupAlertSink
{
   private static final QLogger LOG = QLogger.getLogger(AlertsQBitAlertSink.class);

   private boolean warned = false;



   /*******************************************************************************
    ** Bind the core adapter unless the host already supplied a backup sink.
    *******************************************************************************/
   public static boolean bindIfAbsent()
   {
      if(!(BackupsRuntime.getAlertSink() instanceof NoOpBackupAlertSink))
      {
         return (false);
      }
      BackupsRuntime.setAlertSink(new AlertsQBitAlertSink());
      return (true);
   }



   /*******************************************************************************
    ** Declare missing backup alert types on each instance, independently of sink
    ** installation order. Returns how many were added.
    *******************************************************************************/
   public int registerAlertTypes(QInstance qInstance)
   {
      int added = 0;
      for(BackupAlerts.AlertType alertType : BackupAlerts.alertTypes())
      {
         try
         {
            if(QAlertTypeMetaData.of(qInstance, alertType.name()) == null)
            {
               new QAlertTypeMetaData()
                  .withName(alertType.name())
                  .withLabel(alertType.label())
                  .withGroupName("Backups")
                  .withHowToFix(alertType.howToFix())
                  .withDefaultSeverity(AlertSeverity.valueOf(alertType.defaultSeverity().name()))
                  .addSelfToInstance(qInstance);
               added++;
            }
         }
         catch(RuntimeException | LinkageError e)
         {
            warnOnce("Could not declare the alert type " + alertType.name(), e);
         }
      }
      return (added);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void raise(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail)
   {
      try
      {
         if(!isAlertsQBitInstalled())
         {
            return;
         }
         AlertSignal signal = new AlertSignal(type)
            .withDedupKey(dedupeKey)
            .withSeverity(AlertSeverity.valueOf(severity.name()))
            .withTitle(title);
         if(detail != null && !detail.isEmpty())
         {
            signal.withSummary(detail.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")));
            detail.forEach(signal::withAttribute);
         }
         QAlerts.raise(signal);
      }
      catch(RuntimeException | LinkageError e)
      {
         warnOnce("Could not raise an alert", e);
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void resolve(String type, String dedupeKey)
   {
      try
      {
         if(isAlertsQBitInstalled())
         {
            QAlerts.resolve(type, dedupeKey, "Cleared by the Backups qbit");
         }
      }
      catch(RuntimeException | LinkageError e)
      {
         warnOnce("Could not resolve an alert", e);
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void heartbeat(String monitorKey, long expectedEverySeconds, long graceSeconds)
   {
      try
      {
         if(isAlertsQBitInstalled())
         {
            QAlerts.heartbeat(monitorKey);
         }
      }
      catch(RuntimeException | LinkageError e)
      {
         warnOnce("Could not check in a heartbeat", e);
      }
   }



   /*******************************************************************************
    ** Check on every call so installing the alerts sink after this qbit works.
    ** Type declarations alone do not mean an alerts implementation is installed.
    *******************************************************************************/
   boolean isAlertsQBitInstalled()
   {
      AlertsInstanceMetaData metaData = AlertsInstanceMetaData.of(QContext.getQInstance());
      return (metaData != null && metaData.getSink() != null);
   }



   /*******************************************************************************
    ** Log adapter failures once; dispatch failures are already logged by core.
    *******************************************************************************/
   private synchronized void warnOnce(String message, Throwable t)
   {
      if(!warned)
      {
         warned = true;
         LOG.warn(message + " (the Alerts qbit adapter will keep failing quietly)", t);
      }
   }
}
