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


import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.actions.processes.RunProcessAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunProcessOutput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;


/*******************************************************************************
 ** Helpers for the lifecycle tests: a synthetic clock that the test moves, an alert
 ** sink that remembers what was raised, and plan creation.
 *******************************************************************************/
public final class LifecycleTestSupport
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private LifecycleTestSupport()
   {
   }



   /*******************************************************************************
    ** A clock the test sets.
    *******************************************************************************/
   public static class MutableClock extends Clock
   {
      private Instant now;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public MutableClock(Instant start)
      {
         this.now = start;
      }



      /*******************************************************************************
       ** Move to an instant.
       *******************************************************************************/
      public void set(Instant instant)
      {
         this.now = instant;
      }



      /*******************************************************************************
       ** Move forward.
       *******************************************************************************/
      public void advance(Duration duration)
      {
         this.now = now.plus(duration);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ZoneId getZone()
      {
         return (ZoneOffset.UTC);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public Clock withZone(ZoneId zone)
      {
         return (this);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public Instant instant()
      {
         return (now);
      }
   }



   /*******************************************************************************
    ** Remembers every alert raised and resolved.
    *******************************************************************************/
   public static class RecordingAlertSink implements BackupAlertSink
   {
      public final List<String> raised   = new ArrayList<>();
      public final List<String> resolved = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void raise(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail)
      {
         raised.add(type + "|" + dedupeKey);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void resolve(String type, String dedupeKey)
      {
         resolved.add(type + "|" + dedupeKey);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void heartbeat(String monitorKey, long expectedEverySeconds, long graceSeconds)
      {
      }
   }



   /*******************************************************************************
    ** Install a synthetic clock on the runtime and the memory buckets.
    *******************************************************************************/
   public static MutableClock installClock(Instant start)
   {
      MutableClock clock = new MutableClock(start);
      BackupsRuntime.setClock(clock);
      BackupsTestUtils.BACKUPS.withClock(clock);
      BackupsTestUtils.MEDIA.withClock(clock);
      return (clock);
   }



   /*******************************************************************************
    ** Put the system clock back.
    *******************************************************************************/
   public static void restoreClock()
   {
      BackupsRuntime.setClock(Clock.systemUTC());
      BackupsTestUtils.BACKUPS.withClock(Clock.systemUTC());
      BackupsTestUtils.MEDIA.withClock(Clock.systemUTC());
   }



   /*******************************************************************************
    ** Insert a plan (through the table, so save validation runs) and return it.
    *******************************************************************************/
   public static QRecord plan(Map<String, Serializable> overrides) throws Exception
   {
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("name", "Nightly", "planKey", "nightly", "isActive", true,
         "scopeType", "INSTANCE", "storageBucketAlias", "backups", "slaHours", 26));
      values.putAll(overrides);
      Integer id = new BackupCatalog().insert(BackupsTables.PLAN, values);
      return (new BackupCatalog().get(BackupsTables.PLAN, id).orElseThrow());
   }


   /*******************************************************************************
    ** The two summaries of a streamed ETL run: what Review showed, and what Result said.
    *******************************************************************************/
   public record EtlSummaries(List<ProcessSummaryLine> review, List<ProcessSummaryLine> result, RunProcessOutput output)
   {
      /*******************************************************************************
       ** The text of a summary, count first: "3 runs will be deleted".
       *******************************************************************************/
      public static List<String> texts(List<ProcessSummaryLine> lines, boolean past)
      {
         List<String> texts = new ArrayList<>();
         for(ProcessSummaryLine line : lines)
         {
            texts.add(line.getCount() + " " + (past ? (line.getCount() == 1 ? line.getSingularPastMessage() : line.getPluralPastMessage())
               : (line.getCount() == 1 ? line.getSingularFutureMessage() : line.getPluralFutureMessage())) + (line.getMessageSuffix() == null ? "" : line.getMessageSuffix()));
         }
         return (texts);
      }
   }



   /*******************************************************************************
    ** Run a streamed ETL process of the qbit over the records of a table matching a
    ** field, through Review and then Result.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   public static EtlSummaries runEtl(String processName, String field, List<? extends Serializable> values) throws QException
   {
      RunProcessInput input = new RunProcessInput();
      input.setProcessName(processName);
      input.addValue(StreamedETLWithFrontendProcess.FIELD_DEFAULT_QUERY_FILTER, new QQueryFilter(new QFilterCriteria(field, QCriteriaOperator.IN, values)));
      ///////////////////////////////////////////////////////////////////////////////////
      // the processes validate every record in the first call (full validation is on), //
      // so Review is ready after it; the second call executes and gives the Result     //
      ///////////////////////////////////////////////////////////////////////////////////
      RunProcessOutput output = new RunProcessAction().execute(input);
      List<ProcessSummaryLine> review = (List<ProcessSummaryLine>) output.getValues().get(StreamedETLWithFrontendProcess.FIELD_VALIDATION_SUMMARY);

      input.addValue("processUUID", output.getProcessUUID());
      input.setStartAfterStep("review");
      output = new RunProcessAction().execute(input);
      List<ProcessSummaryLine> result = (List<ProcessSummaryLine>) output.getValues().get(StreamedETLWithFrontendProcess.FIELD_PROCESS_SUMMARY);
      return (new EtlSummaries(review, result, output));
   }
}
