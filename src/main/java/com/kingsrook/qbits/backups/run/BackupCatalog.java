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


import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kingsrook.qbits.backups.files.FileCopier;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Reads and writes the qbit own tables (the catalog) through the standard QQQ
 ** actions.  Every table is a normal, visible QQQ table; this class only wraps
 ** the calls the processes make, under a permission bypass (the callers have
 ** already checked backups.* permissions).
 *******************************************************************************/
public class BackupCatalog
{

   /*******************************************************************************
    ** Insert one row, returning its id.
    *******************************************************************************/
   public Integer insert(String table, Map<String, Serializable> values) throws QException
   {
      QRecord record = new QRecord();
      record.setValues(new LinkedHashMap<>(values));
      InsertInput input = new InsertInput(table);
      input.setRecords(List.of(record));
      input.withPermissionBypass("backups catalog");
      List<QRecord> rs = new InsertAction().execute(input).getRecords();
      if(rs.get(0).getErrors() != null && !rs.get(0).getErrors().isEmpty())
      {
         throw (new QException("Could not write to " + table + ": " + rs.get(0).getErrors().get(0).getMessage()));
      }
      return (rs.get(0).getValueInteger("id"));
   }



   /*******************************************************************************
    ** Update some fields of one row.
    *******************************************************************************/
   public void update(String table, Integer id, Map<String, Serializable> values) throws QException
   {
      Map<String, Serializable> all = new LinkedHashMap<>(values);
      all.put("id", id);
      QRecord record = new QRecord();
      record.setValues(all);
      UpdateInput input = new UpdateInput(table);
      input.setRecords(List.of(record));
      input.withPermissionBypass("backups catalog");
      List<QRecord> rs = new UpdateAction().execute(input).getRecords();
      if(rs.get(0).getErrors() != null && !rs.get(0).getErrors().isEmpty())
      {
         throw (new QException("Could not update " + table + " " + id + ": " + rs.get(0).getErrors().get(0).getMessage()));
      }
   }



   /*******************************************************************************
    ** One row by id.
    *******************************************************************************/
   public Optional<QRecord> get(String table, Integer id) throws QException
   {
      return (query(table, new QQueryFilter(new QFilterCriteria("id", QCriteriaOperator.EQUALS, id))).stream().findFirst());
   }



   /*******************************************************************************
    ** Query a table (catalog tables are small; no paging).
    *******************************************************************************/
   public List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      QueryInput input = new QueryInput(table);
      input.setFilter(filter);
      input.withPermissionBypass("backups catalog");
      return (new QueryAction().execute(input).getRecords());
   }



   /*******************************************************************************
    ** Query by one equals criterion, newest first by id.
    *******************************************************************************/
   public List<QRecord> where(String table, String field, Serializable value) throws QException
   {
      return (query(table, new QQueryFilter(new QFilterCriteria(field, QCriteriaOperator.EQUALS, value)).withOrderBy(new QFilterOrderBy("id", false))));
   }



   /*******************************************************************************
    ** Insert one artifact row.
    *******************************************************************************/
   public Integer insertArtifact(Integer runId, String type, String tableName, Integer partNumber, String objectKey, String format, String compression,
                                 boolean encrypted, long rows, long bytesRaw, long bytesStored, String sha256Stored, String sha256Raw, String rowsHash,
                                 String firstKey, String lastKey, String tableFingerprint) throws QException
   {
      Map<String, Serializable> v = new LinkedHashMap<>();
      v.put("backupRunId", runId);
      v.put("artifactType", type);
      v.put("tableName", tableName);
      v.put("partNumber", partNumber);
      v.put("objectKey", objectKey);
      v.put("format", format);
      v.put("compression", compression);
      v.put("encrypted", encrypted);
      v.put("rowCount", rows);
      v.put("bytesRaw", bytesRaw);
      v.put("bytesStored", bytesStored);
      v.put("sha256Stored", sha256Stored);
      v.put("sha256Raw", sha256Raw);
      v.put("rowsHash", rowsHash);
      v.put("firstKey", firstKey);
      v.put("lastKey", lastKey);
      v.put("tableFingerprint", tableFingerprint);
      v.put("status", "COMPLETE");
      return (insert(BackupsTables.ARTIFACT, v));
   }



   /*******************************************************************************
    ** The file store registry over backups_file_object: known hashes are those with
    ** a live (not deleted) row.
    *******************************************************************************/
   public FileCopier.FileStoreRegistry fileStoreRegistry(String bucketAlias, Integer runId, String keyFingerprint)
   {
      return (new FileCopier.FileStoreRegistry()
      {
         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public boolean isKnown(String sha256)
         {
            try
            {
               return (existing(sha256).isPresent());
            }
            catch(QException e)
            {
               throw (new IllegalStateException("Could not look up a stored file: " + e.getMessage(), e));
            }
         }



         /*******************************************************************************
          ** {@inheritDoc}
          *******************************************************************************/
         @Override
         public void register(String sha256, long sizeBytes, String contentType, String casKey, boolean encrypted)
         {
            try
            {
               Optional<QRecord> existing = existing(sha256);
               if(existing.isPresent())
               {
                  return;
               }
               List<QRecord> deleted = where(BackupsTables.FILE_OBJECT, "sha256", sha256);
               Map<String, Serializable> v = new LinkedHashMap<>();
               v.put("sha256", sha256);
               v.put("sizeBytes", sizeBytes);
               v.put("contentType", contentType);
               v.put("storageBucketAlias", bucketAlias);
               v.put("objectKey", casKey);
               v.put("encrypted", encrypted);
               v.put("encryptionKeyFingerprint", keyFingerprint);
               v.put("firstRunId", runId);
               v.put("lastReferencedRunId", runId);
               v.put("referenceCount", 0);
               v.put("deletedDate", null);
               if(deleted.isEmpty())
               {
                  insert(BackupsTables.FILE_OBJECT, v);
               }
               else
               {
                  update(BackupsTables.FILE_OBJECT, deleted.get(0).getValueInteger("id"), v);
               }
            }
            catch(QException e)
            {
               throw (new IllegalStateException("Could not record a stored file: " + e.getMessage(), e));
            }
         }



         /*******************************************************************************
          **
          *******************************************************************************/
         private Optional<QRecord> existing(String sha256) throws QException
         {
            return (where(BackupsTables.FILE_OBJECT, "sha256", sha256).stream().filter(r -> r.getValue("deletedDate") == null).findFirst());
         }
      });
   }



   /*******************************************************************************
    ** The runs that are RUNNING for a plan, with a heartbeat newer than the cutoff.
    *******************************************************************************/
   public List<QRecord> liveRunsForPlan(Integer planId, Instant heartbeatCutoff) throws QException
   {
      List<QRecord> live = new ArrayList<>();
      for(QRecord run : where(BackupsTables.RUN, "backupPlanId", planId))
      {
         Instant heartbeat = run.getValueInstant("heartbeatDate");
         if("RUNNING".equals(run.getValueString("status")) && heartbeat != null && heartbeat.isAfter(heartbeatCutoff))
         {
            live.add(run);
         }
      }
      return (live);
   }
}
