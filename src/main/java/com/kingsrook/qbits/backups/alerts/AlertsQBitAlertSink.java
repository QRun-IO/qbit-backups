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


import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qqq.backend.core.alerts.AlertSeverity;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlertTypeMetaData;
import com.kingsrook.qqq.backend.core.alerts.QAlerts;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
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

   /** The Alerts qbit's alert type table, whose notify switch decides what reaches the bell. */
   private static final String ALERTS_TYPE_TABLE = "alerts_type";

   private boolean warned = false;

   private final Set<String> silenced = ConcurrentHashMap.newKeySet();



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
         keepAlertOnly(type);
      }
      catch(RuntimeException | LinkageError e)
      {
         warnOnce("Could not raise an alert", e);
      }
   }



   /*******************************************************************************
    ** The core alert type metadata has no notify setting; the Alerts qbit keeps it
    ** on the alert type row (created with notify on, then left to admins).  For a
    ** type declared alert-only, switch notify off once per adapter, after the
    ** first raise has created the row, so these never reach the notification bell.
    ** Without the Alerts qbit's type table this does nothing.
    *******************************************************************************/
   private void keepAlertOnly(String type)
   {
      if(type == null || silenced.contains(type)
         || BackupAlerts.alertTypes().stream().noneMatch(t -> t.name().equals(type) && !t.bell()))
      {
         return;
      }

      try
      {
         if(QContext.getQInstance().getTable(ALERTS_TYPE_TABLE) == null)
         {
            return;
         }

         QueryInput queryInput = new QueryInput(ALERTS_TYPE_TABLE);
         queryInput.setFilter(new QQueryFilter(new QFilterCriteria("name", QCriteriaOperator.EQUALS, type)));
         queryInput.withPermissionBypass("backups alert-only types");
         List<QRecord> rows = new QueryAction().execute(queryInput).getRecords();
         if(rows.isEmpty())
         {
            return;
         }

         if(!Boolean.FALSE.equals(rows.get(0).getValueBoolean("notify")))
         {
            UpdateInput updateInput = new UpdateInput(ALERTS_TYPE_TABLE);
            updateInput.setRecords(List.of(new QRecord().withValue("id", rows.get(0).getValue("id")).withValue("notify", false)));
            updateInput.withPermissionBypass("backups alert-only types");
            new UpdateAction().execute(updateInput);
         }
         silenced.add(type);
      }
      catch(QException | RuntimeException e)
      {
         warnOnce("Could not mark the alert type " + type + " alert-only", e);
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
