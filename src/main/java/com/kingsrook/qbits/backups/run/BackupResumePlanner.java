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


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.codec.TableArtifactWriter;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Works out whether a failed run can be resumed, and from where (spec 9, Resume).
 **
 ** Only a run that read each table on its own (PER_TABLE, or BEST_EFFORT while no
 ** backend gives a snapshot) can resume: a run that held one snapshot cannot carry
 ** on across a new one, and says so.  A resume continues the SAME run (same run key
 ** and prefix): finished tables are kept, and the interrupted table carries on from
 ** the last key of its last complete part (the catalog records each part the moment
 ** it is complete).
 *******************************************************************************/
public final class BackupResumePlanner
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupResumePlanner()
   {
   }



   /*******************************************************************************
    ** Why the run cannot be resumed, or null when it can.
    *******************************************************************************/
   public static String whyNotResumable(QRecord run, QRecord plan)
   {
      String status = run.getValueString("status");
      if(!List.of("FAILED", "TIMED_OUT", "ABANDONED").contains(status))
      {
         return ("Only a failed, timed out or abandoned run can be resumed (this one is " + status + ").");
      }
      if(run.getValueInteger("backupPlanId") == null || plan == null)
      {
         return ("Only a run of a plan can be resumed. Start a new export instead.");
      }
      if(run.getValueString("manifestKey") != null)
      {
         return ("This run already finished: it has a manifest.");
      }
      String achieved = run.getValueString("consistencyAchieved");
      String requested = plan.getValueString("consistency");
      boolean snapshot = "INSTANCE_SNAPSHOT".equals(achieved) || "BACKEND_SNAPSHOT".equals(achieved) || (achieved == null && "SNAPSHOT_REQUIRED".equals(requested));
      if(snapshot)
      {
         return ("This run read from one consistent snapshot, which cannot be carried on after it failed. Run the plan again to take a new backup.");
      }
      return (null);
   }



   /*******************************************************************************
    ** What the run already wrote.
    *******************************************************************************/
   public static ResumeState plan(QRecord run) throws QException
   {
      BackupCatalog catalog = new BackupCatalog();
      List<QRecord> artifacts = catalog.query(BackupsTables.ARTIFACT, new QQueryFilter(
         new QFilterCriteria("backupRunId", QCriteriaOperator.EQUALS, run.getValueInteger("id")),
         new QFilterCriteria("artifactType", QCriteriaOperator.EQUALS, "TABLE_DATA"))
         .withOrderBy(new QFilterOrderBy("tableName", true)).withOrderBy(new QFilterOrderBy("partNumber", true)).withOrderBy(new QFilterOrderBy("id", true)));

      String runPrefix = run.getValueString("runPrefix");
      Map<String, List<QRecord>> byTable = new LinkedHashMap<>();
      for(QRecord artifact : artifacts)
      {
         byTable.computeIfAbsent(artifact.getValueString("tableName"), k -> new ArrayList<>()).add(artifact);
      }

      Map<String, ResumeState.TableState> tables = new LinkedHashMap<>();
      for(Map.Entry<String, List<QRecord>> entry : byTable.entrySet())
      {
         List<TableArtifactWriter.PartResult> parts = new ArrayList<>();
         List<Integer>                        ids   = new ArrayList<>();
         long                                 rows  = 0;
         String                               hash  = null;
         for(QRecord a : entry.getValue())
         {
            parts.add(new TableArtifactWriter.PartResult(a.getValueInteger("partNumber"), runPrefix + a.getValueString("objectKey"), a.getValueLong("rowCount"),
               a.getValueLong("bytesRaw"), a.getValueLong("bytesStored"), a.getValueString("sha256Raw"), a.getValueString("sha256Stored"),
               a.getValueString("firstKey"), a.getValueString("lastKey"), Boolean.TRUE.equals(a.getValueBoolean("encrypted")), a.getValueString("format"),
               a.getValueString("compression")));
            ids.add(a.getValueInteger("id"));
            if(a.getValueString("format") != null && a.getValueString("format").equals(entry.getValue().get(0).getValueString("format")))
            {
               rows += a.getValueLong("rowCount");
            }
            hash = a.getValueString("rowsHash");
         }
         tables.put(entry.getKey(), new ResumeState.TableState(hash != null, parts, hash, rows, ids));
      }
      return (new ResumeState(run.getValueInteger("id"), run.getValueString("runKey"), runPrefix, tables));
   }
}
