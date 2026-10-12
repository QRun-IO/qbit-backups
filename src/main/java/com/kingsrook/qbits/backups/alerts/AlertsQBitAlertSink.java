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


import java.lang.reflect.Method;
import java.util.Map;
import java.util.stream.Collectors;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The BackupAlertSink adapter for the Alerts qbit producer SPI (QRun-IO/qqq
 ** PR #1108, com.kingsrook.qqq.backend.core.alerts: QAlerts, AlertSignal,
 ** AlertResolution, AlertSeverity, QAlertTypeMetaData; design spec
 ** docs/specs/2026-10-12-qbit-alerts-design.md).
 **
 ** STAND-IN: the SPI is an open core PR and is not in QQQ 4.1.0-RC.4, so this
 ** adapter is bound REFLECTIVELY and optionally.  It compiles against nothing in
 ** the SPI; it looks the classes up by name, and every call is guarded:
 ** <ul>
 **    <li>no SPI classes on the class path: {@link #bindIfPresent()} binds nothing
 **    and the sink stays the no-op;</li>
 **    <li>the SPI is present but the instance has no alerts meta-data (the Alerts
 **    qbit is not installed): every call is a no-op;</li>
 **    <li>any reflective or SPI failure is logged once and swallowed, because an
 **    alerting problem must never fail a backup.</li>
 ** </ul>
 ** Replace with a direct binding (QAlerts.raise and friends) when RC.5 ships.
 **
 ** Mapping: raise to QAlerts.raise(AlertSignal), resolve to QAlerts.resolve(type,
 ** dedupKey, note), heartbeat to QAlerts.heartbeat(key).  The SPI has no per-plan
 ** heartbeat registration (monitors are declared on the alert type), so the
 ** expected interval and grace are not passed on; the SLA checker raises
 ** backups.plan.missed itself, independent of any heartbeat (spec 10.4).
 *******************************************************************************/
public class AlertsQBitAlertSink implements BackupAlertSink
{
   private static final QLogger LOG = QLogger.getLogger(AlertsQBitAlertSink.class);

   /** The package of the Alerts producer SPI in qqq-backend-core. */
   public static final String DEFAULT_API_PACKAGE = "com.kingsrook.qqq.backend.core.alerts";

   private final String apiPackage;

   private boolean warned = false;

   private Class<?> qAlerts;
   private Class<?> alertSignal;
   private Class<?> alertSeverity;
   private Class<?> instanceMetaData;



   /*******************************************************************************
    ** Constructor for the real SPI package.
    *******************************************************************************/
   public AlertsQBitAlertSink()
   {
      this(DEFAULT_API_PACKAGE);
   }



   /*******************************************************************************
    ** Constructor for another package (a test double of the SPI shape).
    *******************************************************************************/
   public AlertsQBitAlertSink(String apiPackage)
   {
      this.apiPackage = apiPackage;
   }



   /*******************************************************************************
    ** Whether the SPI classes are on the class path.
    *******************************************************************************/
   public static boolean isPresent(String apiPackage)
   {
      try
      {
         Class.forName(apiPackage + ".QAlerts");
         Class.forName(apiPackage + ".AlertSignal");
         return (true);
      }
      catch(ClassNotFoundException | LinkageError e)
      {
         return (false);
      }
   }



   /*******************************************************************************
    ** Bind this adapter as the runtime's alert sink when the SPI is present and no
    ** other sink was bound already.  Returns whether it bound.
    *******************************************************************************/
   public static boolean bindIfPresent()
   {
      if(!(BackupsRuntime.getAlertSink() instanceof NoOpBackupAlertSink) || !isPresent(DEFAULT_API_PACKAGE))
      {
         return (false);
      }
      BackupsRuntime.setAlertSink(new AlertsQBitAlertSink());
      return (true);
   }



   /*******************************************************************************
    ** Declare the backup alert types with the Alerts qbit (QAlertTypeMetaData), when
    ** the SPI is present.  Returns how many were added.
    *******************************************************************************/
   public int registerAlertTypes(QInstance qInstance)
   {
      int added = 0;
      try
      {
         Class<?> typeClass = Class.forName(apiPackage + ".QAlertTypeMetaData");
         load();
         for(BackupAlerts.AlertType alertType : BackupAlerts.alertTypes())
         {
            try
            {
               Object type = typeClass.getConstructor().newInstance();
               typeClass.getMethod("withName", String.class).invoke(type, alertType.name());
               typeClass.getMethod("withLabel", String.class).invoke(type, alertType.label());
               typeClass.getMethod("withGroupName", String.class).invoke(type, "Backups");
               typeClass.getMethod("withHowToFix", String.class).invoke(type, alertType.howToFix());
               typeClass.getMethod("withDefaultSeverity", alertSeverity).invoke(type, severity(alertType.defaultSeverity()));
               typeClass.getMethod("addSelfToInstance", QInstance.class).invoke(type, qInstance);
               added++;
            }
            catch(ReflectiveOperationException | RuntimeException e)
            {
               warnOnce("Could not declare the alert type " + alertType.name(), e);
            }
         }
      }
      catch(ReflectiveOperationException | LinkageError e)
      {
         warnOnce("The alerts SPI is not available", e);
      }
      return (added);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void raise(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail)
   {
      if(!isAlertsQBitInstalled())
      {
         return;
      }
      try
      {
         Object signal = alertSignal.getConstructor(String.class).newInstance(type);
         alertSignal.getMethod("setDedupKey", String.class).invoke(signal, dedupeKey);
         alertSignal.getMethod("setSeverity", alertSeverity).invoke(signal, severity(severity));
         alertSignal.getMethod("setTitle", String.class).invoke(signal, title);
         if(detail != null && !detail.isEmpty())
         {
            alertSignal.getMethod("setSummary", String.class).invoke(signal, detail.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", ")));
            Method withAttribute = alertSignal.getMethod("withAttribute", String.class, String.class);
            for(Map.Entry<String, String> entry : detail.entrySet())
            {
               withAttribute.invoke(signal, entry.getKey(), entry.getValue());
            }
         }
         qAlerts.getMethod("raise", alertSignal).invoke(null, signal);
      }
      catch(ReflectiveOperationException | RuntimeException e)
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
      if(!isAlertsQBitInstalled())
      {
         return;
      }
      try
      {
         qAlerts.getMethod("resolve", String.class, String.class, String.class).invoke(null, type, dedupeKey, "Cleared by the Backups qbit");
      }
      catch(ReflectiveOperationException | RuntimeException e)
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
      if(!isAlertsQBitInstalled())
      {
         return;
      }
      try
      {
         qAlerts.getMethod("heartbeat", String.class).invoke(null, monitorKey);
      }
      catch(ReflectiveOperationException | RuntimeException e)
      {
         warnOnce("Could not check in a heartbeat", e);
      }
   }



   /*******************************************************************************
    ** The SPI classes load, and the instance has alerts meta-data (the Alerts qbit
    ** is installed).  Checked on every call so the order qbits are produced in
    ** does not matter.
    *******************************************************************************/
   boolean isAlertsQBitInstalled()
   {
      try
      {
         load();
         QInstance qInstance = QContext.getQInstance();
         if(qInstance == null)
         {
            return (false);
         }
         return (instanceMetaData.getMethod("of", QInstance.class).invoke(null, qInstance) != null);
      }
      catch(ReflectiveOperationException | RuntimeException | LinkageError e)
      {
         warnOnce("The alerts SPI is not usable", e);
         return (false);
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private synchronized void load() throws ClassNotFoundException
   {
      if(qAlerts == null)
      {
         qAlerts = Class.forName(apiPackage + ".QAlerts");
         alertSignal = Class.forName(apiPackage + ".AlertSignal");
         alertSeverity = Class.forName(apiPackage + ".AlertSeverity");
         instanceMetaData = Class.forName(apiPackage + ".AlertsInstanceMetaData");
      }
   }



   /*******************************************************************************
    ** Map our severity to the SPI's enum constant of the same name.
    *******************************************************************************/
   @SuppressWarnings({ "unchecked", "rawtypes" })
   private Object severity(Severity severity)
   {
      return (Enum.valueOf((Class<Enum>) alertSeverity, severity.name()));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void warnOnce(String message, Throwable t)
   {
      if(!warned)
      {
         warned = true;
         LOG.warn(message + " (the Alerts qbit adapter will keep failing quietly)", t, logPair("apiPackage", apiPackage));
      }
   }
}
