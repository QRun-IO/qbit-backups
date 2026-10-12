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

package com.kingsrook.qbits.backups.metrics;


import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** The optional Prometheus text for operators who already run Alertmanager (design
 ** spec section 10.4), computed from the tables and cached for 60 seconds.  Gauges,
 ** all labeled {instance_name, plan}:
 ** <ul>
 **    <li>qqq_backup_last_success_timestamp_seconds</li>
 **    <li>qqq_backup_last_run_failed (1 when the latest finished run failed)</li>
 **    <li>qqq_backup_sla_seconds</li>
 **    <li>qqq_backup_last_verify_success_timestamp_seconds</li>
 **    <li>qqq_backup_last_success_bytes</li>
 ** </ul>
 ** The rule worth having even alongside the Alerts qbit is
 ** absent_over_time(qqq_backup_last_success_timestamp_seconds[30m]): the one signal
 ** that still fires when the whole app, and so the Alerts qbit with it, is down.
 **
 ** ENDPOINT STUB: this class renders the text.  Serving it at GET /metrics/backups
 ** (when BackupsQBitConfig.withPrometheusEndpoint(true)) is a route in
 ** qqq-middleware-javalin, which this qbit does not depend on; the app's Javalin
 ** route provider calls {@link #renderCached()}.  {@link #isEnabled()} says whether
 ** the config asks for the endpoint.
 *******************************************************************************/
public final class BackupsMetrics
{
   public static final String ENDPOINT_PATH = "/metrics/backups";

   private static final Duration CACHE = Duration.ofSeconds(60);

   private static String  cachedText;
   private static Instant cachedAt;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsMetrics()
   {
   }



   /*******************************************************************************
    ** Whether the config asks for the endpoint.
    *******************************************************************************/
   public static boolean isEnabled()
   {
      BackupsQBitConfig config = BackupsRuntime.getConfig();
      return (config != null && Boolean.TRUE.equals(config.getPrometheusEndpoint()));
   }



   /*******************************************************************************
    ** Forget the cached text (tests).
    *******************************************************************************/
   public static synchronized void resetCache()
   {
      cachedText = null;
      cachedAt = null;
   }



   /*******************************************************************************
    ** The text, from the cache when it is under 60 seconds old.
    *******************************************************************************/
   public static synchronized String renderCached() throws QException
   {
      Instant now = BackupsRuntime.getClock().instant();
      if(cachedText == null || cachedAt == null || cachedAt.plus(CACHE).isBefore(now))
      {
         cachedText = render();
         cachedAt = now;
      }
      return (cachedText);
   }



   /*******************************************************************************
    ** Render the text format now.
    *******************************************************************************/
   public static String render() throws QException
   {
      BackupCatalog     catalog  = new BackupCatalog();
      BackupsQBitConfig config   = BackupsRuntime.getConfig();
      String            instance = config != null && config.getInstanceName() != null ? config.getInstanceName() : "qqq";

      StringBuilder lastSuccess = header("qqq_backup_last_success_timestamp_seconds", "Unix time of the last successful backup run");
      StringBuilder lastFailed  = header("qqq_backup_last_run_failed", "1 when the latest finished run of the plan failed");
      StringBuilder sla         = header("qqq_backup_sla_seconds", "Maximum age of the last successful run before the plan is late");
      StringBuilder lastVerify  = header("qqq_backup_last_verify_success_timestamp_seconds", "Unix time of the last passed content or test-restore verification");
      StringBuilder lastBytes   = header("qqq_backup_last_success_bytes", "Bytes stored by the last successful run");

      List<QRecord> plans = catalog.query(BackupsTables.PLAN, new QQueryFilter(new QFilterCriteria("isActive", QCriteriaOperator.EQUALS, true)));
      for(QRecord plan : plans)
      {
         String labels = "{instance_name=\"" + escape(instance) + "\",plan=\"" + escape(plan.getValueString("planKey")) + "\"}";

         if(plan.getValueInstant("lastSuccessDate") != null)
         {
            lastSuccess.append("qqq_backup_last_success_timestamp_seconds").append(labels).append(' ').append(plan.getValueInstant("lastSuccessDate").getEpochSecond()).append('\n');
         }
         List<QRecord> runs = catalog.where(BackupsTables.RUN, "backupPlanId", plan.getValueInteger("id"));
         QRecord latest = runs.stream().filter(r -> List.of("SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "FAILED", "TIMED_OUT", "ABANDONED").contains(r.getValueString("status"))).findFirst().orElse(null);
         if(latest != null)
         {
            boolean failed = List.of("FAILED", "TIMED_OUT", "ABANDONED").contains(latest.getValueString("status"));
            lastFailed.append("qqq_backup_last_run_failed").append(labels).append(' ').append(failed ? 1 : 0).append('\n');
         }
         Integer slaHours = plan.getValueInteger("slaHours");
         if(slaHours != null)
         {
            sla.append("qqq_backup_sla_seconds").append(labels).append(' ').append(slaHours * 3600L).append('\n');
         }
         if(plan.getValueInstant("lastVerifiedDate") != null)
         {
            lastVerify.append("qqq_backup_last_verify_success_timestamp_seconds").append(labels).append(' ').append(plan.getValueInstant("lastVerifiedDate").getEpochSecond()).append('\n');
         }
         if(plan.getValueInteger("lastSuccessRunId") != null)
         {
            QRecord run = catalog.get(BackupsTables.RUN, plan.getValueInteger("lastSuccessRunId")).orElse(null);
            if(run != null && run.getValueLong("bytesStored") != null)
            {
               lastBytes.append("qqq_backup_last_success_bytes").append(labels).append(' ').append(run.getValueLong("bytesStored")).append('\n');
            }
         }
      }
      return (lastSuccess.toString() + lastFailed + sla + lastVerify + lastBytes);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static StringBuilder header(String name, String help)
   {
      return (new StringBuilder("# HELP ").append(name).append(' ').append(help).append('\n').append("# TYPE ").append(name).append(" gauge\n"));
   }



   /*******************************************************************************
    ** Escape a label value for the text format.
    *******************************************************************************/
   static String escape(String value)
   {
      return (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"));
   }
}
