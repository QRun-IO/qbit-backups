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
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissionChecker;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupEngine;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qbits.backups.run.BackupRunRequest;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qbits.backups.scope.TableRole;
import com.kingsrook.qbits.backups.scope.TableRoles;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.actions.audits.AuditAction;
import com.kingsrook.qqq.backend.core.actions.tables.DeleteAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Restores from a backup run (spec 9.3 to 9.5).  Two entry points share the same
 ** table pass:  review() reads and compares without writing anything, and
 ** execute() takes the pre-restore backup, writes in restore order, resyncs key
 ** sequences, brings files back (re-hashing each against the index), writes one
 ** summary audit per table, and records backups_restore_run and
 ** backups_restore_run_table rows.
 **
 ** Safety rails (spec 9.4) are enforced here, not only in the screens:  the caller
 ** needs backups.restore (REPLACE also backups.admin and the typed confirmation),
 ** SECRET and BACKUPS tables are never restored, EPHEMERAL tables only when named,
 ** and nothing restores while a backup of the same tables is running.
 *******************************************************************************/
public class RestoreEngine
{
   private static final QLogger LOG = QLogger.getLogger(RestoreEngine.class);

   private static final long RUNNING_HEARTBEAT_MINUTES = 10;

   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    ** What a request resolves to: the source, the tables in restore order and the
    ** table each is written to, with every rail violation as an error.
    *******************************************************************************/
   public record Plan(BackupSource source, List<String> order, Map<String, String> targets, List<String> errors, List<String> warnings, List<String> notes)
   {
   }



   /*******************************************************************************
    ** Resolve a request and check every safety rail, without touching any data.
    *******************************************************************************/
   public Plan plan(RestoreRequest request)
   {
      List<String>        errors   = new ArrayList<>();
      List<String>        warnings = new ArrayList<>();
      List<String>        notes    = new ArrayList<>();
      List<String>        order    = new ArrayList<>();
      Map<String, String> targets  = new LinkedHashMap<>();
      QInstance           instance = QContext.getQInstance();

      if(!BackupsPermissionChecker.has(BackupsPermissions.RESTORE)
         || (request.getWriter().usesCoreRestoreFlag() && (QContext.getQSession() == null || !QContext.getQSession().hasPermission(BackupsPermissions.RESTORE))))
      {
         errors.add("Restoring needs the " + BackupsPermissions.RESTORE + " permission.");
      }
      boolean replace = RestoreRequest.MODE_REPLACE.equals(request.getMode());
      if(!List.of(RestoreRequest.MODE_INSERT_NEW, RestoreRequest.MODE_UPSERT, RestoreRequest.MODE_REPLACE).contains(request.getMode()))
      {
         errors.add("Unknown restore mode " + request.getMode() + ".");
      }
      if(!List.of(RestoreRequest.IDS_PRESERVE, RestoreRequest.IDS_REMAP).contains(request.getIdStrategy()))
      {
         errors.add("Unknown id strategy " + request.getIdStrategy() + ".");
      }
      if(replace && !BackupsPermissionChecker.has(BackupsPermissions.ADMIN))
      {
         errors.add("A REPLACE restore needs the " + BackupsPermissions.ADMIN + " permission as well.");
      }
      if(replace && RestoreRequest.IDS_REMAP.equals(request.getIdStrategy()))
      {
         errors.add("REPLACE cannot be combined with new ids: use PRESERVE ids.");
      }

      BackupSource source = null;
      try
      {
         source = BackupSource.open(request.getSourceRunId());
      }
      catch(QException e)
      {
         errors.add(e.getMessage());
         return (new Plan(null, order, targets, errors, warnings, notes));
      }

      boolean explicit = !request.getTables().isEmpty();
      List<String> names = new ArrayList<>();
      if(explicit)
      {
         for(String name : request.getTables())
         {
            if(source.getManifest().table(name) == null)
            {
               errors.add("The table " + name + " is not in this backup.");
            }
            else
            {
               names.add(name);
            }
         }
      }
      else
      {
         source.getManifest().tables().forEach(t -> names.add(t.name()));
      }

      if(request.getTargetTable() != null && names.size() != 1)
      {
         errors.add("A target table can only be chosen when exactly one table is restored.");
      }

      for(String name : names)
      {
         String         targetName = request.getTargetTable() != null && names.size() == 1 ? request.getTargetTable() : name;
         QTableMetaData target     = instance.getTable(targetName);
         if(target == null)
         {
            errors.add("The table " + targetName + " does not exist in this instance.");
            continue;
         }
         TableRole role = TableRoles.roleOf(instance, target);
         if(role == TableRole.SECRET || role == TableRole.BACKUPS)
         {
            errors.add("The table " + targetName + " is a " + role + " table, and restores never touch those.");
            continue;
         }
         if(role == TableRole.EPHEMERAL)
         {
            if(!explicit)
            {
               notes.add("The table " + targetName + " holds temporary data and is left out unless you choose it.");
               continue;
            }
            warnings.add("The table " + targetName + " holds temporary data; you chose to restore it anyway.");
         }
         targets.put(name, targetName);
      }

      List<String> manifestOrder = source.getManifest().restoreOrder() == null ? List.of() : source.getManifest().restoreOrder();
      for(String name : manifestOrder)
      {
         if(targets.containsKey(name))
         {
            order.add(name);
         }
      }
      for(String name : targets.keySet())
      {
         if(!order.contains(name))
         {
            order.add(name);
         }
      }

      if(order.isEmpty() && errors.isEmpty())
      {
         errors.add("There are no tables to restore.");
      }

      if(replace && errors.isEmpty())
      {
         String expected = RestoreRequest.expectedConfirmation(order);
         if(request.getConfirmationText() == null || !expected.equals(request.getConfirmationText().trim()))
         {
            errors.add("To confirm a REPLACE, type " + expected + ".");
         }
      }

      String running = runningBackupFor(order);
      if(running != null)
      {
         errors.add(running);
      }

      return (new Plan(source, order, targets, errors, warnings, notes));
   }



   /*******************************************************************************
    ** A message when a backup that covers any of these tables is running, else null.
    *******************************************************************************/
   private String runningBackupFor(List<String> tables)
   {
      try
      {
         Instant cutoff = BackupsRuntime.getClock().instant().minusSeconds(RUNNING_HEARTBEAT_MINUTES * 60);
         for(QRecord run : catalog.where(BackupsTables.RUN, "status", "RUNNING"))
         {
            Instant heartbeat = run.getValueInstant("heartbeatDate");
            if(heartbeat == null || !heartbeat.isAfter(cutoff))
            {
               continue;
            }
            if(coversAny(run, tables))
            {
               return ("A backup of these tables is running (" + run.getValueString("runKey") + "). Wait for it to finish, then restore.");
            }
         }
         return (null);
      }
      catch(QException e)
      {
         return ("Could not check for running backups: " + e.getMessage());
      }
   }



   /*******************************************************************************
    ** Whether a running backup covers any of the tables (a run without a plan is
    ** assumed to).
    *******************************************************************************/
   private boolean coversAny(QRecord run, List<String> tables) throws QException
   {
      Integer planId = run.getValueInteger("backupPlanId");
      if(planId == null)
      {
         return (true);
      }
      QRecord plan = catalog.get(BackupsTables.PLAN, planId).orElse(null);
      if(plan == null)
      {
         return (true);
      }
      Set<String> covered = new HashSet<>(BackupRunFactory.resolveScope(plan).names());
      return (tables.stream().anyMatch(covered::contains));
   }



   /*******************************************************************************
    ** Review: everything the restore would do, with nothing written.
    *******************************************************************************/
   public RestoreOutcome review(RestoreRequest request) throws QException
   {
      Plan plan = plan(request);
      if(!plan.errors().isEmpty())
      {
         RestoreOutcome outcome = new RestoreOutcome();
         outcome.getErrors().addAll(plan.errors());
         return (outcome);
      }

      RestoreOutcome outcome = new RestorePass(request, plan.source(), true, null, plan.order(), plan.targets()).run();
      outcome.getWarnings().addAll(plan.warnings());
      outcome.getNotes().addAll(plan.notes());
      if(!request.getPreRestoreBackup())
      {
         outcome.getWarnings().add("The safety backup before the restore is turned off.");
      }
      if(RestoreRequest.IDS_PRESERVE.equals(request.getIdStrategy()) && outcome.sum(t -> t.getSequenceNeeded() ? 1 : 0) > 0 && !request.getWriter().canResyncSequences())
      {
         outcome.getWarnings().add("Key sequences cannot be resynced with this version of QQQ (it needs the sequence resync hook), so on databases with generated keys the next generated id may collide. Resync them after the restore.");
      }
      if(!request.getWriter().usesCoreRestoreFlag())
      {
         outcome.getNotes().add("This version of QQQ has no restore mode for writes, so rows are written with the standard actions: record security and validation apply, and modified dates may be set to now.");
      }
      return (outcome);
   }



   /*******************************************************************************
    ** Execute the restore.  Throws when a rail refuses it; otherwise returns the
    ** outcome (also recorded in the restore run rows).
    *******************************************************************************/
   public RestoreOutcome execute(RestoreRequest request) throws QException
   {
      Plan plan = plan(request);
      if(!plan.errors().isEmpty())
      {
         throw (new QException(String.join(" ", plan.errors())));
      }

      BackupsQBitConfig config = BackupsRuntime.getConfig();
      Instant           start  = BackupsRuntime.getClock().instant();

      Map<String, Serializable> row = new LinkedHashMap<>();
      row.put("sourceBackupRunId", request.getSourceRunId());
      row.put("requestedByUserId", request.getRequestedBy());
      row.put("startDate", start);
      row.put("status", "RUNNING");
      row.put("mode", request.getMode());
      row.put("idStrategy", request.getIdStrategy());
      row.put("matchOn", request.getMatchOn());
      row.put("targetInstanceName", config.getInstanceName() != null ? config.getInstanceName() : "qqq");
      row.put("scopeSummary", scopeSummary(request, plan));
      row.put("automationsSuppressed", request.getSuppressAutomations());
      row.put("esbSuppressed", !request.getPublishEvents());
      row.put("confirmationText", RestoreRequest.MODE_REPLACE.equals(request.getMode()) ? request.getConfirmationText() : null);
      Integer restoreRunId = catalog.insert(BackupsTables.RESTORE_RUN, row);

      RestoreOutcome outcome = null;
      try
      {
         Integer preRestoreRunId = null;
         if(request.getPreRestoreBackup())
         {
            preRestoreRunId = preRestoreBackup(request, plan);
            catalog.update(BackupsTables.RESTORE_RUN, restoreRunId, Map.of("preRestoreBackupRunId", preRestoreRunId));
         }

         RestorePass pass = new RestorePass(request, plan.source(), false, restoreRunId, plan.order(), plan.targets());
         outcome = pass.run();
         outcome.setRestoreRunId(restoreRunId);
         outcome.setPreRestoreRunId(preRestoreRunId);
         outcome.getWarnings().addAll(plan.warnings());
         outcome.getNotes().addAll(plan.notes());

         resyncSequences(request, plan, outcome);
         restoreFiles(request, plan, pass, outcome, restoreRunId);
         recordResults(request, plan, outcome, restoreRunId, start);
         deleteIdMap(restoreRunId);
         return (outcome);
      }
      catch(QException | RuntimeException e)
      {
         LOG.warn("Restore failed", e, logPair("restoreRunId", restoreRunId));
         String message = e.getClass().getSimpleName() + ": " + e.getMessage();
         try
         {
            Map<String, Serializable> failed = new LinkedHashMap<>();
            failed.put("status", "FAILED");
            failed.put("endDate", BackupsRuntime.getClock().instant());
            failed.put("errorMessage", message.length() > 2000 ? message.substring(0, 2000) : message);
            catalog.update(BackupsTables.RESTORE_RUN, restoreRunId, failed);
         }
         catch(QException inner)
         {
            LOG.warn("Could not record the failed restore", inner);
         }
         throw (e);
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private String scopeSummary(RestoreRequest request, Plan plan)
   {
      StringBuilder sb = new StringBuilder(String.join(", ", plan.order()));
      if(!request.getFilters().isEmpty())
      {
         sb.append(" | filters on ").append(String.join(", ", request.getFilters().keySet()));
      }
      if(request.getTargetTable() != null)
      {
         sb.append(" | into ").append(request.getTargetTable());
      }
      if(!request.getPreRestoreBackup())
      {
         sb.append(" | no pre-restore backup");
      }
      return (sb.toString());
   }



   /*******************************************************************************
    ** Back up the target scope (with its files) before writing anything.
    *******************************************************************************/
   private Integer preRestoreBackup(RestoreRequest request, Plan plan) throws QException
   {
      BackupSource  source = plan.source();
      BackupsQBitConfig config = BackupsRuntime.getConfig();
      String        alias  = source.getRun().getValueString("storageBucketAlias");

      PlanSettings settings = PlanSettings.adHoc().with("scopeType", "TABLES").with("planKey", "pre-restore");
      Map<String, Serializable> original = source.getManifest().plan() == null ? Map.of() : source.getManifest().plan().settings();
      for(String key : List.of("prefix", "format", "compression", "pageSize", "targetPartBytes", "fileObjectPolicy", "heavyFieldPolicy", "consistency"))
      {
         if(original != null && original.get(key) != null)
         {
            settings.with(key, original.get(key));
         }
      }
      boolean encrypts = source.getTransform().encrypts();
      if(encrypts)
      {
         settings.with("encryptionMode", "CLIENT_AES256_GCM").with("sensitiveFieldPolicy", "INCLUDE_ENCRYPTED_ONLY");
      }

      List<ScopeResolver.ScopeRule> rules = new ArrayList<>();
      for(String name : plan.order())
      {
         rules.add(new ScopeResolver.ScopeRule(true, "TABLE", plan.targets().get(name), request.filterFor(name), false, null));
      }

      BackupRunRequest backup = new BackupRunRequest()
         .withSettings(settings)
         .withScope(new ScopeResolver(QContext.getQInstance()).resolve(settings, rules))
         .withTrigger("PRE_RESTORE")
         .withRequestedBy(request.getRequestedBy())
         .withBucketAlias(alias)
         .withStorage(source.getStorage())
         .withSourceResolver(BackupsRuntime::storageFor)
         .withInstanceName(config.getInstanceName() != null ? config.getInstanceName() : "qqq")
         .withPlanName("Pre-restore backup")
         .withClock(BackupsRuntime.getClock());
      BackupManifest.EncryptionInfo encryption = source.getManifest().encryption();
      backup.withTransform(source.getTransform(), encrypts && encryption != null ? encryption.keyRef() : null, encrypts && encryption != null ? encryption.keyFingerprint() : null);

      BackupRunResult result = new BackupEngine().execute(backup);
      if(!result.isUsable())
      {
         throw (new QException("The backup taken before the restore failed, so nothing was restored: " + result.getErrorMessage()));
      }
      return (result.getRunId());
   }



   /*******************************************************************************
    ** Advance the key sequences of the tables written with their original ids.
    *******************************************************************************/
   private void resyncSequences(RestoreRequest request, Plan plan, RestoreOutcome outcome) throws QException
   {
      List<QTableMetaData> tables = new ArrayList<>();
      for(RestoreOutcome.TableOutcome table : outcome.getTables().values())
      {
         if(table.getSequenceNeeded())
         {
            tables.add(QContext.getQInstance().getTable(table.getTargetName()));
         }
      }
      if(tables.isEmpty())
      {
         return;
      }
      if(!request.getWriter().canResyncSequences())
      {
         outcome.getWarnings().add("Key sequences were not resynced: this version of QQQ has no resync hook.");
         return;
      }
      List<String> resynced = request.getWriter().resyncSequences(tables);
      outcome.setSequencesResynced(resynced.size());
      for(RestoreOutcome.TableOutcome table : outcome.getTables().values())
      {
         table.setSequenceResynced(resynced.contains(table.getTargetName()));
      }
   }



   /*******************************************************************************
    ** Copy files back from the content-addressed store, re-hashing each against the
    ** index.  A conflicting file (different content at the target) is only replaced
    ** by a REPLACE restore.
    *******************************************************************************/
   private void restoreFiles(RestoreRequest request, Plan plan, RestorePass pass, RestoreOutcome outcome, Integer restoreRunId)
   {
      for(RestorePass.FileWork work : pass.getFileWork())
      {
         if(work.conflict() && !RestoreRequest.MODE_REPLACE.equals(request.getMode()))
         {
            continue;
         }
         RestoreOutcome.TableOutcome table = outcome.getTables().get(work.table());
         try
         {
            copyFileBack(plan.source(), work, restoreRunId);
            table.addFilesRestored(1);
            table.addFileBytesRestored(Math.max(0, work.size()));
         }
         catch(IOException | RuntimeException e)
         {
            outcome.addFilesFailed(1);
            table.getProblems().add("The file " + work.targetKey() + " was not restored: " + e.getMessage());
         }
      }
   }



   /*******************************************************************************
    ** Write one file to a temporary key, check its SHA-256 against the index, and
    ** only then put it in place.
    *******************************************************************************/
   private void copyFileBack(BackupSource source, RestorePass.FileWork work, Integer restoreRunId) throws IOException
   {
      BackupStorage target = BackupsRuntime.storageFor(work.targetAlias());
      String        temp   = work.targetKey() + ".qqq-restore-" + restoreRunId;

      String observed;
      try(InputStream in = source.getTransform().unwrap(source.getStorage().openRead(work.casKey()));
          HashingOutputStream out = new HashingOutputStream(target.openWrite(temp, WriteOptions.create().withMetadata("qqq-restore-run", String.valueOf(restoreRunId)))))
      {
         in.transferTo(out);
         out.close();
         observed = out.getSha256();
      }

      if(!work.sha256().equals(observed))
      {
         target.deletePermanent(temp);
         throw (new IOException("checksum mismatch after reading the stored copy"));
      }
      target.copy(temp, work.targetKey());
      target.deletePermanent(temp);
   }



   /*******************************************************************************
    ** One summary audit per table, the per-table rows, and the totals on the run.
    *******************************************************************************/
   private void recordResults(RestoreRequest request, Plan plan, RestoreOutcome outcome, Integer restoreRunId, Instant start) throws QException
   {
      String runKey = plan.source().getRun().getValueString("runKey");
      for(RestoreOutcome.TableOutcome t : outcome.getTables().values())
      {
         Map<String, Serializable> row = new LinkedHashMap<>();
         row.put("backupRestoreRunId", restoreRunId);
         row.put("tableName", t.getName());
         row.put("targetTableName", t.getTargetName());
         row.put("rowsInserted", t.getInserted());
         row.put("rowsUpdated", t.getUpdated());
         row.put("rowsUnchanged", t.getUnchanged());
         row.put("rowsDeleted", t.getDeleted());
         row.put("rowsSkipped", t.getSkipped() + t.getConflicts());
         row.put("rowsErrored", t.getErrored());
         row.put("schemaStatus", t.getSchemaStatus());
         row.put("unmappedFields", String.join(", ", t.getUnmappedFields()));
         row.put("sequenceResynced", t.getSequenceResynced());
         row.put("status", t.getStatus());
         catalog.insert(BackupsTables.RESTORE_RUN_TABLE, row);

         if(!"SKIPPED".equals(t.getStatus()))
         {
            summaryAudit(t, runKey, restoreRunId);
         }
      }

      boolean warnings = outcome.sum(t -> t.getErrored()) > 0 || outcome.getFilesFailed() > 0 || !outcome.getWarnings().isEmpty()
         || outcome.getTables().values().stream().anyMatch(t -> "SKIPPED".equals(t.getStatus()));

      Map<String, Serializable> totals = new LinkedHashMap<>();
      totals.put("status", warnings ? "SUCCEEDED_WITH_WARNINGS" : "SUCCEEDED");
      totals.put("endDate", BackupsRuntime.getClock().instant());
      totals.put("rowsInserted", outcome.sum(RestoreOutcome.TableOutcome::getInserted));
      totals.put("rowsUpdated", outcome.sum(RestoreOutcome.TableOutcome::getUpdated));
      totals.put("rowsUnchanged", outcome.sum(RestoreOutcome.TableOutcome::getUnchanged));
      totals.put("rowsDeleted", outcome.sum(RestoreOutcome.TableOutcome::getDeleted));
      totals.put("rowsSkipped", outcome.sum(t -> t.getSkipped() + t.getConflicts()));
      totals.put("rowsErrored", outcome.sum(RestoreOutcome.TableOutcome::getErrored));
      totals.put("filesRestored", (int) outcome.sum(t -> t.getFilesRestored()));
      totals.put("filesAlreadyPresent", (int) outcome.sum(t -> t.getFilesPresent()));
      totals.put("filesConflicting", (int) outcome.sum(t -> t.getFilesConflicting()));
      totals.put("filesMissingFromBackup", (int) outcome.sum(t -> t.getFilesMissing()));
      totals.put("fileBytesRestored", outcome.sum(RestoreOutcome.TableOutcome::getFileBytesRestored));
      totals.put("sequencesResynced", outcome.getSequencesResynced());
      catalog.update(BackupsTables.RESTORE_RUN, restoreRunId, totals);
   }



   /*******************************************************************************
    ** The one audit entry for a table in a restore (replaces per-record audit).
    *******************************************************************************/
   private void summaryAudit(RestoreOutcome.TableOutcome t, String runKey, Integer restoreRunId)
   {
      try
      {
         AuditAction.execute(t.getTargetName(), null, Map.of(), "Restore " + restoreRunId + " from backup " + runKey + ": "
            + t.getInserted() + " inserted, " + t.getUpdated() + " updated, " + t.getDeleted() + " deleted, " + t.getUnchanged() + " unchanged");
      }
      catch(RuntimeException e)
      {
         LOG.warn("Could not write the restore summary audit", e, logPair("table", t.getTargetName()));
      }
   }



   /*******************************************************************************
    ** The id map rows are only needed while the restore runs.
    *******************************************************************************/
   private void deleteIdMap(Integer restoreRunId)
   {
      try
      {
         DeleteInput input = new DeleteInput(BackupsTables.ID_MAP);
         input.setQueryFilter(new QQueryFilter(new QFilterCriteria("restoreRunId", QCriteriaOperator.EQUALS, restoreRunId)));
         input.withPermissionBypass("backups restore id map");
         new DeleteAction().execute(input);
      }
      catch(QException | RuntimeException e)
      {
         LOG.warn("Could not clear the id map of a restore", e, logPair("restoreRunId", restoreRunId));
      }
   }
}
