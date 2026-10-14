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

package com.kingsrook.qbits.backups.restore;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.codec.FieldValueCodec;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.files.FileReference;
import com.kingsrook.qbits.backups.files.FileReferenceProvider;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.manifest.TableFingerprints;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.utils.BackendQueryFilterUtils;
import com.kingsrook.qqq.backend.core.utils.JsonUtils;


/*******************************************************************************
 ** One pass of a restore over the selected tables, in restore order (spec 9.3).
 ** The same algorithm backs Review (dryRun: nothing is written) and Execute, so
 ** the Review numbers are exactly what the restore does.
 **
 ** For each table the backup rows are streamed in pages and compared to the target
 ** rows found by match key.  Rows are classified as insert, update, unchanged or
 ** skipped; unique-key conflicts and orphaned references are counted; REPLACE also
 ** finds the target rows that are not in the backup.  When writing, each page is its
 ** own transaction (REPLACE uses one per table so it is never left half empty),
 ** and the files the rows reference are collected for the file restore.
 **
 ** Memory is O(page), plus the key sets of the tables that are REPLACEd or are
 ** parents of the restored tables (keys only, never rows).
 *******************************************************************************/
public class RestorePass
{
   private static final int  MAX_SAMPLES        = 20;
   private static final int  MAX_TEXT           = 60;
   private static final int  MAX_PROBLEMS       = 5;
   private static final char SEP                = '\u0001';

   private final RestoreRequest      request;
   private final BackupSource        source;
   private final boolean             dryRun;
   private final Integer             restoreRunId;
   private final List<String>        order;
   private final Map<String, String> targets;
   private final QInstance           qInstance = QContext.getQInstance();

   private final RestoreOutcome                outcome  = new RestoreOutcome();
   private final Map<String, Set<String>>      pkKeySets = new HashMap<>();
   private final Map<String, Map<String, Serializable>> idMaps = new HashMap<>();
   private final List<FileWork>                fileWork = new ArrayList<>();
   private final Set<String>                   seenFiles = new HashSet<>();
   private final Map<String, List<Pending>>    retry    = new LinkedHashMap<>();
   private Map<String, JsonNode>               fileIndex = Map.of();



   /*******************************************************************************
    ** A row waiting to be inserted, with the primary key it had in the backup.
    *******************************************************************************/
   record Pending(QRecord record, String oldPkText)
   {
   }



   /*******************************************************************************
    ** A file the restore will copy back from the content-addressed store.
    *******************************************************************************/
   public record FileWork(String table, String targetAlias, String targetKey, String sha256, String casKey, long size, boolean conflict)
   {
   }



   /*******************************************************************************
    ** Context of the table being restored.
    *******************************************************************************/
   private static class TableContext
   {
      private QTableMetaData target;
      private BackupManifest.TableEntry entry;
      private RestoreOutcome.TableOutcome outcome;
      private String name;
      private String pk;
      private List<String> matchFields;
      private boolean matchIsPk;
      private boolean remap;
      private Map<String, String> foreignKeys;
      private Set<String> deletePkTexts = new HashSet<>();
      private QBackendTransaction tableTransaction;
   }



   /*******************************************************************************
    ** Wraps a checked failure so it can cross a row sink.
    *******************************************************************************/
   private static class Failure extends RuntimeException
   {
      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      Failure(QException cause)
      {
         super(cause);
      }
   }



   /*******************************************************************************
    ** Constructor.  The order is the restore order of the selected tables and the
    ** targets map says which table each is written to.  The restoreRunId is only
    ** needed when writing with REMAP_NEW_IDS (it keys the id map rows).
    *******************************************************************************/
   public RestorePass(RestoreRequest request, BackupSource source, boolean dryRun, Integer restoreRunId, List<String> order, Map<String, String> targets)
   {
      this.request = request;
      this.source = source;
      this.dryRun = dryRun;
      this.restoreRunId = restoreRunId;
      this.order = order;
      this.targets = targets;
   }



   /*******************************************************************************
    ** The files collected for restore (valid after run()).
    *******************************************************************************/
   public List<FileWork> getFileWork()
   {
      return (fileWork);
   }



   /*******************************************************************************
    ** Run the pass.
    *******************************************************************************/
   public RestoreOutcome run() throws QException
   {
      if(request.getRestoreFiles() && source.getManifest().files() != null && source.getManifest().files().indexKeys() != null)
      {
         fileIndex = source.fileIndex(new HashSet<>(order));
      }

      for(String name : order)
      {
         JobChunkScope.checkBetweenPages();
         restoreTable(name);
      }
      retryPass();
      return (outcome);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void restoreTable(String name) throws QException
   {
      TableContext c = new TableContext();
      c.name = name;
      c.target = qInstance.getTable(targets.get(name));
      c.entry = source.getManifest().table(name);
      c.pk = c.target.getPrimaryKeyField();
      c.outcome = outcome.table(name, c.target.getName());
      c.remap = RestoreRequest.IDS_REMAP.equals(request.getIdStrategy());
      analyzeSchema(c);
      if("MISMATCH".equals(c.outcome.getSchemaStatus()))
      {
         c.outcome.setStatus("SKIPPED");
         return;
      }

      c.matchFields = matchFields(c);
      c.matchIsPk = c.matchFields.equals(List.of(c.pk));
      c.foreignKeys = foreignKeys(c.target);
      if(c.remap)
      {
         c.outcome.getUnmappedReferences().addAll(untraceableReferences(c));
         if(c.matchIsPk)
         {
            c.outcome.getSchemaNotes().add("REMAP_NEW_IDS matches on the primary key, so every row is inserted as new; match on a unique key to reuse existing rows");
         }
      }

      QQueryFilter filter = parseFilter(request.filterFor(name));
      List<Serializable> deletePks = new ArrayList<>();
      if(RestoreRequest.MODE_REPLACE.equals(request.getMode()))
      {
         findDeletes(c, filter, deletePks);
         c.outcome.addDeleted(deletePks.size());
      }

      try
      {
         if(!dryRun && RestoreRequest.MODE_REPLACE.equals(request.getMode()))
         {
            c.tableTransaction = QBackendTransaction.openFor(new InsertInput(c.target.getName()));
            for(int i = 0; i < deletePks.size(); i += request.getPageSize())
            {
               request.getWriter().delete(c.target, deletePks.subList(i, Math.min(deletePks.size(), i + request.getPageSize())), request, c.tableTransaction);
            }
         }

         List<QRecord> buffer = new ArrayList<>();
         try
         {
            source.readRows(c.entry, c.target, row ->
            {
               row.getValues().keySet().removeIf(k -> !c.target.getFields().containsKey(k));
               if(filter != null && !BackendQueryFilterUtils.doesRecordMatch(filter, row))
               {
                  return;
               }
               buffer.add(row);
               if(buffer.size() >= request.getPageSize())
               {
                  flush(c, buffer);
               }
            });
            flush(c, buffer);
         }
         catch(Failure f)
         {
            throw ((QException) f.getCause());
         }

         if(c.tableTransaction != null)
         {
            c.tableTransaction.commit();
         }
      }
      catch(QException | RuntimeException e)
      {
         if(c.tableTransaction != null)
         {
            c.tableTransaction.rollback();
         }
         throw (e);
      }
      finally
      {
         if(c.tableTransaction != null)
         {
            c.tableTransaction.close();
         }
      }

      boolean keyed = c.target.getField(c.pk).getType() == QFieldType.INTEGER || c.target.getField(c.pk).getType() == QFieldType.LONG;
      c.outcome.setSequenceNeeded(keyed && !c.remap && (c.outcome.getInserted() > 0 || RestoreRequest.MODE_REPLACE.equals(request.getMode())));
      c.outcome.setStatus("DONE");
   }



   /*******************************************************************************
    ** Flush the buffered page (and empty the buffer).
    *******************************************************************************/
   private void flush(TableContext c, List<QRecord> buffer)
   {
      if(buffer.isEmpty())
      {
         return;
      }
      try
      {
         processPage(c, new ArrayList<>(buffer));
      }
      catch(QException e)
      {
         throw (new Failure(e));
      }
      buffer.clear();
   }



   /*******************************************************************************
    ** Compare the backup schema of the table with the target.
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private void analyzeSchema(TableContext c)
   {
      QInstance instance = QContext.getQInstance();
      String    backendType = instance.getBackend(c.target.getBackendName()) == null ? null : instance.getBackend(c.target.getBackendName()).getBackendType();
      RestoreOutcome.TableOutcome o = c.outcome;

      if(TableFingerprints.matches(c.entry, c.target, backendType))
      {
         o.setSchemaStatus("MATCH");
         return;
      }

      Map<String, Object> backupFields = TableFingerprints.fields(c.entry.schema());
      Map<String, Object> targetFields = TableFingerprints.fields(TableFingerprints.descriptor(c.target, backendType));
      boolean incompatible = false;
      boolean different    = false;

      for(Map.Entry<String, Object> field : backupFields.entrySet())
      {
         if(!targetFields.containsKey(field.getKey()))
         {
            o.getUnmappedFields().add(field.getKey());
            o.getSchemaNotes().add("field " + field.getKey() + " is in the backup but not in the target and will be skipped");
            different = true;
            continue;
         }
         Map<String, Object> was = (Map<String, Object>) field.getValue();
         Map<String, Object> now = (Map<String, Object>) targetFields.get(field.getKey());
         if(!String.valueOf(was.get("type")).equals(String.valueOf(now.get("type"))))
         {
            incompatible = true;
            o.getSchemaNotes().add("field " + field.getKey() + " changed type from " + was.get("type") + " to " + now.get("type"));
         }
         else if(!was.equals(now))
         {
            different = true;
            o.getSchemaNotes().add("field " + field.getKey() + " changed (length, required, possible values or flags)");
         }
      }

      List<String> excluded = c.entry.excludedFields() == null ? List.of() : c.entry.excludedFields().stream().map(BackupManifest.ExcludedField::name).toList();
      for(String field : targetFields.keySet())
      {
         if(!backupFields.containsKey(field))
         {
            QFieldMetaData now = c.target.getField(field);
            different = true;
            if(now.getIsRequired() && !excluded.contains(field) && !field.equals(c.target.getPrimaryKeyField()))
            {
               incompatible = true;
               o.getSchemaNotes().add("required field " + field + " is in the target but not in the backup");
            }
            else
            {
               o.getSchemaNotes().add("field " + field + " is in the target but not in the backup and keeps its default");
            }
         }
      }

      o.setSchemaStatus(incompatible ? "MISMATCH" : different ? "COMPATIBLE" : "MATCH");
      if(incompatible)
      {
         o.getProblems().add("The table " + c.name + " is not compatible with the backup (see the schema notes), so it is not restored");
      }
   }



   /*******************************************************************************
    ** The fields rows are matched on.
    *******************************************************************************/
   private List<String> matchFields(TableContext c) throws QException
   {
      String matchOn = request.getMatchOn();
      List<String> fields = null;
      if(matchOn == null || matchOn.isBlank() || RestoreRequest.MATCH_PRIMARY_KEY.equals(matchOn))
      {
         fields = List.of(c.pk);
      }
      else
      {
         List<String> wanted = Arrays.stream(matchOn.split("[,+]")).map(String::trim).toList();
         for(UniqueKey key : c.target.getUniqueKeys() == null ? List.<UniqueKey>of() : c.target.getUniqueKeys())
         {
            if(new HashSet<>(key.getFieldNames()).equals(new HashSet<>(wanted)) || matchOn.equals(key.getLabel()))
            {
               fields = key.getFieldNames();
               break;
            }
         }
         if(fields == null && order.size() > 1)
         {
            c.outcome.getSchemaNotes().add("it has no unique key " + matchOn + ", so rows are matched on the primary key");
            fields = List.of(c.pk);
         }
         else if(fields == null)
         {
            throw (new QException("The table " + c.target.getName() + " has no unique key named " + matchOn));
         }
      }
      for(String field : fields)
      {
         if(c.entry.excludedFields() != null && c.entry.excludedFields().stream().anyMatch(f -> f.name().equals(field)))
         {
            throw (new QException("The match field " + field + " of " + c.name + " was left out of the backup, so rows cannot be matched on it"));
         }
      }
      return (fields);
   }



   /*******************************************************************************
    ** The fields that reference another table: those whose possible value source is a
    ** table, and those on the many side of a join.  Field name to parent table name.
    *******************************************************************************/
   private Map<String, String> foreignKeys(QTableMetaData table)
   {
      Map<String, String> fks = new LinkedHashMap<>();
      for(QFieldMetaData field : table.getFields().values())
      {
         if(field.getName().equals(table.getPrimaryKeyField()) || field.getPossibleValueSourceName() == null)
         {
            continue;
         }
         QPossibleValueSource pvs = qInstance.getPossibleValueSource(field.getPossibleValueSourceName());
         if(pvs != null && pvs.getType() == QPossibleValueSourceType.TABLE && pvs.getTableName() != null)
         {
            fks.put(field.getName(), pvs.getTableName());
         }
      }
      for(QJoinMetaData join : qInstance.getJoins().values())
      {
         if(join.getJoinOns() == null || join.getJoinOns().size() != 1)
         {
            continue;
         }
         JoinOn on = join.getJoinOns().get(0);
         if(join.getType() == JoinType.MANY_TO_ONE && table.getName().equals(join.getLeftTable()) && table.getFields().containsKey(on.getLeftField()))
         {
            fks.putIfAbsent(on.getLeftField(), join.getRightTable());
         }
         else if(join.getType() == JoinType.ONE_TO_MANY && table.getName().equals(join.getRightTable()) && table.getFields().containsKey(on.getRightField()))
         {
            fks.putIfAbsent(on.getRightField(), join.getLeftTable());
         }
      }
      return (fks);
   }



   /*******************************************************************************
    ** Id looking fields that metadata cannot trace to a parent table.
    *******************************************************************************/
   private List<String> untraceableReferences(TableContext c)
   {
      List<String> rs = new ArrayList<>();
      for(QFieldMetaData field : c.target.getFields().values())
      {
         boolean idLike = field.getName().endsWith("Id") || field.getName().endsWith("ID");
         boolean numeric = field.getType() == QFieldType.INTEGER || field.getType() == QFieldType.LONG;
         if(idLike && numeric && !field.getName().equals(c.pk) && !c.foreignKeys.containsKey(field.getName()))
         {
            rs.add(c.target.getName() + "." + field.getName());
         }
      }
      return (rs);
   }



   /*******************************************************************************
    ** REPLACE: the target rows (within the filter) that are not in the backup.
    *******************************************************************************/
   private void findDeletes(TableContext c, QQueryFilter filter, List<Serializable> deletePks) throws QException
   {
      Set<String> backupKeys = new HashSet<>();
      try
      {
         source.readRows(c.entry, c.target, row ->
         {
            if(filter == null || BackendQueryFilterUtils.doesRecordMatch(filter, row))
            {
               backupKeys.add(keyText(c.target, c.matchFields, row));
            }
         });
      }
      catch(Failure f)
      {
         throw ((QException) f.getCause());
      }

      Serializable last = null;
      while(true)
      {
         QQueryFilter page = new QQueryFilter();
         if(last != null)
         {
            page.addCriteria(new QFilterCriteria(c.pk, QCriteriaOperator.GREATER_THAN, List.of(last)));
         }
         if(filter != null)
         {
            page.addSubFilter(filter.clone());
         }
         page.withOrderBy(new QFilterOrderBy(c.pk, true));
         page.setLimit(request.getPageSize());
         List<QRecord> rows = query(c.target.getName(), page);
         for(QRecord row : rows)
         {
            if(!backupKeys.contains(keyText(c.target, c.matchFields, row)))
            {
               deletePks.add(row.getValue(c.pk));
               c.deletePkTexts.add(pkText(c, row));
            }
            last = row.getValue(c.pk);
         }
         if(rows.size() < request.getPageSize())
         {
            return;
         }
      }
   }



   /*******************************************************************************
    ** Process one page of backup rows.
    *******************************************************************************/
   private void processPage(TableContext c, List<QRecord> page) throws QException
   {
      JobChunkScope.checkBetweenPages();
      RestoreOutcome.TableOutcome o = c.outcome;
      o.addRows(page.size());

      if(request.getRestoreFiles())
      {
         collectFiles(c, page);
      }
      countOrphans(c, page);

      if(c.remap && !dryRun)
      {
         rewriteReferences(c, page);
      }

      Map<String, QRecord> existing = c.remap && c.matchIsPk ? Map.of() : lookup(c.target, c.matchFields, page);

      List<Pending>   inserts = new ArrayList<>();
      List<QRecord>   updates = new ArrayList<>();
      for(QRecord row : page)
      {
         String  oldPk = row.getValue(c.pk) == null ? null : canonical(c.target.getField(c.pk), row.getValue(c.pk));
         QRecord found = existing.get(keyText(c.target, c.matchFields, row));
         if(found == null)
         {
            inserts.add(new Pending(row, oldPk));
            continue;
         }

         if(c.remap && oldPk != null)
         {
            idMaps.computeIfAbsent(c.name, k -> new HashMap<>()).put(oldPk, found.getValue(c.pk));
         }

         Map<String, Serializable> changes = diff(c, row, found);
         if(changes.isEmpty())
         {
            o.addUnchanged(1);
         }
         else if(RestoreRequest.MODE_INSERT_NEW.equals(request.getMode()))
         {
            o.addSkipped(1);
         }
         else
         {
            recordSample(c, found, changes);
            QRecord update = new QRecord();
            update.setTableName(c.target.getName());
            update.setValue(c.pk, found.getValue(c.pk));
            changes.forEach(update::setValue);
            updates.add(update);
         }
      }

      List<Pending> clean = withoutConflicts(c, inserts);

      if(dryRun)
      {
         o.addInserted(clean.size());
         o.addUpdated(updates.size());
         return;
      }
      write(c, clean, updates);
   }



   /*******************************************************************************
    ** The fields that differ between the backup row and the target row.  The primary
    ** key is not compared unless rows are matched on it.
    *******************************************************************************/
   private Map<String, Serializable> diff(TableContext c, QRecord backupRow, QRecord found)
   {
      Map<String, Serializable> changes = new LinkedHashMap<>();
      for(Map.Entry<String, Serializable> value : backupRow.getValues().entrySet())
      {
         QFieldMetaData field = c.target.getFields().get(value.getKey());
         if(field == null || field.getName().equals(c.pk))
         {
            continue;
         }
         if(!same(field, value.getValue(), found.getValue(field.getName())))
         {
            changes.put(field.getName(), value.getValue());
         }
      }
      return (changes);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean same(QFieldMetaData field, Serializable a, Serializable b)
   {
      if(a == null || b == null)
      {
         return (a == null && b == null);
      }
      if(field.getType() == QFieldType.DECIMAL)
      {
         BigDecimal x = com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsBigDecimal(a);
         BigDecimal y = com.kingsrook.qqq.backend.core.utils.ValueUtils.getValueAsBigDecimal(b);
         return (x.compareTo(y) == 0);
      }
      return (canonical(field, a).equals(canonical(field, b)));
   }



   /*******************************************************************************
    ** Remember a changed record for the sample diff, masking sensitive values.
    *******************************************************************************/
   private void recordSample(TableContext c, QRecord found, Map<String, Serializable> changes)
   {
      c.outcome.countChangedRecord();
      if(c.outcome.getSamples().size() >= MAX_SAMPLES)
      {
         return;
      }
      List<String> lines = new ArrayList<>();
      for(Map.Entry<String, Serializable> change : changes.entrySet())
      {
         QFieldMetaData field = c.target.getField(change.getKey());
         lines.add(change.getKey() + ": " + show(field, found.getValue(change.getKey())) + " -> " + show(field, change.getValue()));
      }
      c.outcome.getSamples().add(new RestoreOutcome.Sample(pkText(c, found), lines));
   }



   /*******************************************************************************
    ** A value as shown in Review: sensitive values are masked, blobs summarized,
    ** long text truncated.
    *******************************************************************************/
   static String show(QFieldMetaData field, Serializable value)
   {
      if(field.getIsSensitive() || field.getType() == QFieldType.PASSWORD)
      {
         return ("********");
      }
      if(value == null)
      {
         return ("(empty)");
      }
      if(field.getType() == QFieldType.BLOB)
      {
         return ("(binary)");
      }
      String text = canonical(field, value).replace('\n', ' ');
      return (text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "..." : text);
   }



   /*******************************************************************************
    ** Inserts that collide on a unique key (or, when matching on a unique key, on the
    ** primary key) with a target row that is staying are conflicts, and are not written.
    *******************************************************************************/
   private List<Pending> withoutConflicts(TableContext c, List<Pending> inserts) throws QException
   {
      if(inserts.isEmpty())
      {
         return (inserts);
      }
      List<List<String>> keys = new ArrayList<>();
      for(UniqueKey key : c.target.getUniqueKeys() == null ? List.<UniqueKey>of() : c.target.getUniqueKeys())
      {
         if(!new HashSet<>(key.getFieldNames()).equals(new HashSet<>(c.matchFields)))
         {
            keys.add(key.getFieldNames());
         }
      }
      if(!c.matchIsPk && !c.remap)
      {
         keys.add(List.of(c.pk));
      }

      Set<Pending> conflicted = new LinkedHashSet<>();
      for(List<String> fields : keys)
      {
         List<QRecord> candidates = inserts.stream().map(Pending::record).filter(r -> keyText(c.target, fields, r) != null).toList();
         if(candidates.isEmpty())
         {
            continue;
         }
         Map<String, QRecord> found = lookup(c.target, fields, candidates);
         for(Pending p : inserts)
         {
            QRecord hit = found.get(keyText(c.target, fields, p.record));
            if(hit != null && !c.deletePkTexts.contains(pkText(c, hit)))
            {
               conflicted.add(p);
            }
         }
      }
      c.outcome.addConflicts(conflicted.size());
      return (inserts.stream().filter(p -> !conflicted.contains(p)).toList());
   }



   /*******************************************************************************
    ** Count the rows whose reference points to a parent that is in neither the
    ** backup (the selected tables) nor the target.
    *******************************************************************************/
   private void countOrphans(TableContext c, List<QRecord> page) throws QException
   {
      Set<Integer> orphanRows = new HashSet<>();
      for(Map.Entry<String, String> fk : c.foreignKeys.entrySet())
      {
         QFieldMetaData field = c.target.getField(fk.getKey());
         String         parent = fk.getValue();
         Set<String>    inBackup = order.contains(parent) ? pkKeysOf(parent) : Set.of();

         Map<Integer, String> missing = new LinkedHashMap<>();
         for(int i = 0; i < page.size(); i++)
         {
            Serializable value = page.get(i).getValue(fk.getKey());
            if(value != null && !inBackup.contains(canonical(field, value)))
            {
               missing.put(i, canonical(field, value));
            }
         }
         if(missing.isEmpty())
         {
            continue;
         }

         Set<String> inTarget = existingKeys(parent, new HashSet<>(missing.values()));
         for(Map.Entry<Integer, String> m : missing.entrySet())
         {
            if(!inTarget.contains(m.getValue()))
            {
               orphanRows.add(m.getKey());
            }
         }
      }
      c.outcome.addOrphans(orphanRows.size());
   }



   /*******************************************************************************
    ** The primary keys (as text) of the rows of a selected table in the backup.
    *******************************************************************************/
   private Set<String> pkKeysOf(String parent) throws QException
   {
      if(pkKeySets.containsKey(parent))
      {
         return (pkKeySets.get(parent));
      }
      Set<String>    keys   = new HashSet<>();
      QTableMetaData target = qInstance.getTable(targets.get(parent));
      QQueryFilter   filter = parseFilter(request.filterFor(parent));
      BackupManifest.TableEntry entry = source.getManifest().table(parent);
      if(entry != null)
      {
         try
         {
            source.readRows(entry, target, row ->
            {
               if(filter == null || BackendQueryFilterUtils.doesRecordMatch(filter, row))
               {
                  keys.add(canonical(target.getField(target.getPrimaryKeyField()), row.getValue(target.getPrimaryKeyField())));
               }
            });
         }
         catch(Failure f)
         {
            throw ((QException) f.getCause());
         }
      }
      pkKeySets.put(parent, keys);
      return (keys);
   }



   /*******************************************************************************
    ** Which of these primary keys (as text) exist in the target table.
    *******************************************************************************/
   private Set<String> existingKeys(String parent, Set<String> keyTexts) throws QException
   {
      Set<String> found = new HashSet<>();
      if(keyTexts.isEmpty() || qInstance.getTable(targets.getOrDefault(parent, parent)) == null)
      {
         return (found);
      }
      QTableMetaData table = qInstance.getTable(targets.getOrDefault(parent, parent));
      QFieldMetaData pk    = table.getField(table.getPrimaryKeyField());
      List<Serializable> values = new ArrayList<>();
      for(String text : keyTexts)
      {
         values.add(FieldValueCodec.fromCanonicalText(pk.getType(), text));
      }
      for(QRecord row : query(table.getName(), new QQueryFilter(new QFilterCriteria(pk.getName(), QCriteriaOperator.IN, values))))
      {
         found.add(canonical(pk, row.getValue(pk.getName())));
      }
      return (found);
   }



   /*******************************************************************************
    ** REMAP: point references at the new ids of the parents restored earlier.
    *******************************************************************************/
   private void rewriteReferences(TableContext c, List<QRecord> page)
   {
      for(Map.Entry<String, String> fk : c.foreignKeys.entrySet())
      {
         Map<String, Serializable> map = idMaps.get(fk.getValue());
         if(map == null)
         {
            continue;
         }
         QFieldMetaData field = c.target.getField(fk.getKey());
         for(QRecord row : page)
         {
            Serializable value = row.getValue(fk.getKey());
            if(value != null && map.containsKey(canonical(field, value)))
            {
               row.setValue(fk.getKey(), map.get(canonical(field, value)));
            }
         }
      }
   }



   /*******************************************************************************
    ** Write a page of inserts and updates in one transaction (or in the table's).
    *******************************************************************************/
   private void write(TableContext c, List<Pending> inserts, List<QRecord> updates) throws QException
   {
      RestoreOutcome.TableOutcome o = c.outcome;
      QBackendTransaction tx = c.tableTransaction;
      boolean ownTransaction = tx == null;
      if(ownTransaction)
      {
         tx = QBackendTransaction.openFor(new InsertInput(c.target.getName()));
      }

      try
      {
         if(!updates.isEmpty())
         {
            for(QRecord out : request.getWriter().update(c.target, updates, request, tx))
            {
               if(hasErrors(out))
               {
                  o.addErrored(1);
                  addProblem(o, out);
               }
               else
               {
                  o.addUpdated(1);
               }
            }
         }

         if(!inserts.isEmpty())
         {
            List<QRecord> records = new ArrayList<>();
            for(Pending p : inserts)
            {
               if(c.remap)
               {
                  p.record.getValues().remove(c.pk);
               }
               records.add(p.record);
            }
            List<QRecord> outs = request.getWriter().insert(c.target, records, request, tx);
            for(int i = 0; i < outs.size(); i++)
            {
               QRecord out = outs.get(i);
               if(hasErrors(out))
               {
                  o.addErrored(1);
                  addProblem(o, out);
                  retry.computeIfAbsent(c.name, k -> new ArrayList<>()).add(inserts.get(i));
               }
               else
               {
                  o.addInserted(1);
                  if(c.remap && inserts.get(i).oldPkText != null)
                  {
                     idMaps.computeIfAbsent(c.name, k -> new HashMap<>()).put(inserts.get(i).oldPkText, out.getValue(c.pk));
                     recordIdMap(c, inserts.get(i).oldPkText, out.getValue(c.pk));
                  }
               }
            }
         }

         if(ownTransaction)
         {
            tx.commit();
         }
      }
      catch(QException | RuntimeException e)
      {
         if(ownTransaction)
         {
            tx.rollback();
         }
         throw (e);
      }
      finally
      {
         if(ownTransaction)
         {
            tx.close();
         }
      }
   }



   /*******************************************************************************
    ** Persist one old-to-new id (visible in backups_id_map until the restore ends).
    *******************************************************************************/
   private void recordIdMap(TableContext c, String oldId, Serializable newId) throws QException
   {
      if(restoreRunId == null)
      {
         return;
      }
      QRecord row = new QRecord();
      row.setValue("restoreRunId", restoreRunId);
      row.setValue("tableName", c.name);
      row.setValue("oldId", oldId);
      row.setValue("newId", String.valueOf(newId));
      InsertInput input = new InsertInput(BackupsTables.ID_MAP);
      input.setRecords(List.of(row));
      input.withPermissionBypass("backups restore id map");
      new InsertAction().execute(input);
   }



   /*******************************************************************************
    ** One more try, after every other table, for the rows that failed to insert (a
    ** database foreign key QQQ does not know about may have needed another table first).
    *******************************************************************************/
   private void retryPass() throws QException
   {
      for(Map.Entry<String, List<Pending>> entry : retry.entrySet())
      {
         RestoreOutcome.TableOutcome o = outcome.getTables().get(entry.getKey());
         QTableMetaData target = qInstance.getTable(o.getTargetName());
         for(Pending p : entry.getValue())
         {
            try(QBackendTransaction tx = QBackendTransaction.openFor(new InsertInput(target.getName())))
            {
               List<QRecord> outs = request.getWriter().insert(target, List.of(p.record), request, tx);
               if(hasErrors(outs.get(0)))
               {
                  tx.rollback();
               }
               else
               {
                  tx.commit();
                  o.addErrored(-1);
                  o.addInserted(1);
                  if(p.oldPkText != null && RestoreRequest.IDS_REMAP.equals(request.getIdStrategy()))
                  {
                     idMaps.computeIfAbsent(entry.getKey(), k -> new HashMap<>()).put(p.oldPkText, outs.get(0).getValue(target.getPrimaryKeyField()));
                  }
               }
            }
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean hasErrors(QRecord record)
   {
      return (record.getErrors() != null && !record.getErrors().isEmpty());
   }



   /*******************************************************************************
    ** Keep the first few distinct error messages (never the row values).
    *******************************************************************************/
   private static void addProblem(RestoreOutcome.TableOutcome o, QRecord out)
   {
      String message = out.getErrors().get(0).getMessage();
      if(o.getProblems().size() < MAX_PROBLEMS && !o.getProblems().contains(message))
      {
         o.getProblems().add(message);
      }
   }



   /*******************************************************************************
    ** Look rows up in the target by a set of fields.  Keys are the canonical key texts.
    *******************************************************************************/
   private Map<String, QRecord> lookup(QTableMetaData table, List<String> fields, List<QRecord> rows) throws QException
   {
      Map<String, QRecord> found = new HashMap<>();
      QQueryFilter filter = new QQueryFilter();
      boolean any = false;
      if(fields.size() == 1)
      {
         Set<Serializable> values = new LinkedHashSet<>();
         for(QRecord row : rows)
         {
            if(row.getValue(fields.get(0)) != null)
            {
               values.add(row.getValue(fields.get(0)));
            }
         }
         any = !values.isEmpty();
         filter.addCriteria(new QFilterCriteria(fields.get(0), QCriteriaOperator.IN, new ArrayList<>(values)));
      }
      else
      {
         filter.setBooleanOperator(QQueryFilter.BooleanOperator.OR);
         for(QRecord row : rows)
         {
            if(keyText(table, fields, row) == null)
            {
               continue;
            }
            QQueryFilter one = new QQueryFilter();
            for(String field : fields)
            {
               one.addCriteria(new QFilterCriteria(field, QCriteriaOperator.EQUALS, row.getValue(field)));
            }
            filter.addSubFilter(one);
            any = true;
         }
      }
      if(!any)
      {
         return (found);
      }
      for(QRecord row : query(table.getName(), filter))
      {
         String key = keyText(table, fields, row);
         if(key != null)
         {
            found.put(key, row);
         }
      }
      return (found);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private List<QRecord> query(String table, QQueryFilter filter) throws QException
   {
      QueryInput input = new QueryInput(table);
      input.setFilter(filter);
      input.setShouldGenerateDisplayValues(false);
      input.setShouldTranslatePossibleValues(false);
      input.setShouldMaskPasswords(false);
      input.setShouldOmitHiddenFields(false);
      input.setShouldFetchHeavyFields(true);
      return (new QueryAction().execute(input).getRecords());
   }



   /*******************************************************************************
    ** The canonical text of a key made of these fields, or null when any is null.
    *******************************************************************************/
   private static String keyText(QTableMetaData table, List<String> fields, QRecord row)
   {
      StringBuilder sb = new StringBuilder();
      for(String field : fields)
      {
         Serializable value = row.getValue(field);
         if(value == null)
         {
            return (null);
         }
         sb.append(canonical(table.getField(field), value)).append(SEP);
      }
      return (sb.toString());
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String pkText(TableContext c, QRecord row)
   {
      return (canonical(c.target.getField(c.pk), row.getValue(c.pk)));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String canonical(QFieldMetaData field, Serializable value)
   {
      return (FieldValueCodec.toCanonicalText(field.getType(), value));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static QQueryFilter parseFilter(String json) throws QException
   {
      try
      {
         return (json == null || json.isBlank() ? null : JsonUtils.toObject(json, QQueryFilter.class));
      }
      catch(IOException e)
      {
         throw (new QException("Could not read a table filter: " + e.getMessage()));
      }
   }



   /*******************************************************************************
    ** The files the rows of this page reference: check them against the target and
    ** keep the ones to restore.
    *******************************************************************************/
   private void collectFiles(TableContext c, List<QRecord> page) throws QException
   {
      for(QRecord row : page)
      {
         for(FileReferenceProvider provider : request.getFileProviders())
         {
            for(FileReference ref : provider.referencesIn(c.target, row))
            {
               collectFile(c, ref);
            }
         }
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void collectFile(TableContext c, FileReference ref) throws QException
   {
      RestoreOutcome.TableOutcome o = c.outcome;
      JsonNode node = fileIndex.get(BackupSource.indexKeyOf(c.name, ref.recordKey(), ref.field()));
      String   status = node == null ? "MISSING" : node.path("status").asText();
      if(!"COPIED".equals(status) && !"DEDUPLICATED".equals(status))
      {
         o.addFilesMissing(1);
         return;
      }

      String alias = request.getFileTargetBucketAlias() != null ? request.getFileTargetBucketAlias() : ref.bucketAlias();
      String key   = (request.getFileTargetPrefix() == null ? "" : request.getFileTargetPrefix()) + ref.key();
      if(!seenFiles.add(alias + SEP + key))
      {
         return;
      }

      BackupStorage storage;
      try
      {
         storage = BackupsRuntime.storageFor(alias);
      }
      catch(RuntimeException e)
      {
         o.addFilesMissing(1);
         addFileProblem(o, "No storage is configured for the bucket alias " + alias);
         return;
      }

      long   size   = node.path("size").asLong(-1);
      String sha256 = node.path("sha256").asText();
      var    head   = storage.head(key);
      if(head.isEmpty())
      {
         o.addFilesToRestore(1);
         fileWork.add(new FileWork(c.name, alias, key, sha256, node.path("casKey").asText(), size, false));
      }
      else if(head.get().size() == size && sha256.equals(hashOf(storage, key)))
      {
         o.addFilesPresent(1);
      }
      else
      {
         o.addFilesConflicting(1);
         fileWork.add(new FileWork(c.name, alias, key, sha256, node.path("casKey").asText(), size, true));
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void addFileProblem(RestoreOutcome.TableOutcome o, String message)
   {
      if(o.getProblems().size() < MAX_PROBLEMS && !o.getProblems().contains(message))
      {
         o.getProblems().add(message);
      }
   }



   /*******************************************************************************
    ** SHA-256 of a stored object (null when it cannot be read).
    *******************************************************************************/
   private static String hashOf(BackupStorage storage, String key)
   {
      try(InputStream in = storage.openRead(key);
          HashingOutputStream sink = new HashingOutputStream(OutputStream.nullOutputStream()))
      {
         in.transferTo(sink);
         sink.close();
         return (sink.getSha256());
      }
      catch(IOException | RuntimeException e)
      {
         return (null);
      }
   }
}
