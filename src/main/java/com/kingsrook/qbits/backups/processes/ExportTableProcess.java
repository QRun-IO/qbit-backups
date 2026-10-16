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

package com.kingsrook.qbits.backups.processes;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissionChecker;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.run.BackupRunRequest;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.run.TableReader;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qbits.backups.scope.TableRole;
import com.kingsrook.qbits.backups.scope.TableRoles;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import static com.kingsrook.qbits.backups.processes.BackupsProcesses.line;


/*******************************************************************************
 ** Back up these records (spec 9, 11.3): a table and bulk action on any table the
 ** user can read.  It writes an ad-hoc backups_run (no plan) that restores like any
 ** other, with referenced files copied the same way.
 **
 ** - The records are whatever the table action selected (the chosen records or the
 **   current filter), read as the user, so record locks apply and the manifest says
 **   securityScope USER.
 ** - Sensitive fields are left out unless the user has backups.admin.
 ** - The destination is the bucket given in the process input (storageBucketAlias),
 **   else the bucket of the newest active plan.
 **
 ** The records are held in memory until the export runs (an export of selected
 ** records is bounded by what a person selected); a whole table is what a plan is for.
 **
 ** The process is added to each table that can be backed up, named
 ** table.backupExportTable (see exportProcesses).
 *******************************************************************************/
public final class ExportTableProcess
{
   public static final String NAME_SUFFIX = "backupExportTable";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private ExportTableProcess()
   {
   }



   /*******************************************************************************
    ** The export process for one table.
    *******************************************************************************/
   public static QProcessMetaData processFor(String tableName)
   {
      return (BackupsProcesses.permission(BackupsProcesses.etl(tableName + "." + NAME_SUFFIX, "Back up these records", tableName, Transform.class, Load.class,
         "This is a preview of what will be backed up. Nothing is written until you continue."), BackupsPermissions.RUN));
   }



   /*******************************************************************************
    ** The export process of every table of the instance that can be backed up (not the
    ** qbit own tables, and not secrets or sessions).
    *******************************************************************************/
   public static List<QProcessMetaData> exportProcesses(QInstance qInstance)
   {
      List<QProcessMetaData> processes = new ArrayList<>();
      for(QTableMetaData table : qInstance.getTables().values())
      {
         if(table.getName().startsWith(BackupsQBitConfig.TABLE_NAME_PREFIX))
         {
            continue;
         }
         TableRole role = TableRoles.roleOf(qInstance, table);
         if((role == TableRole.DATA || role == TableRole.AUDIT || role == TableRole.DERIVED) && TableRoles.isQueryable(qInstance, table))
         {
            processes.add(processFor(table.getName()));
         }
      }
      return (processes);
   }



   /*******************************************************************************
    ** The sensitive field policy for the user.
    *******************************************************************************/
   static String sensitivePolicy()
   {
      return (BackupsPermissionChecker.has(BackupsPermissions.ADMIN) ? "INCLUDE" : "EXCLUDE");
   }



   /*******************************************************************************
    ** The bucket the export goes to, or null when there is none.
    *******************************************************************************/
   static String bucketAlias(RunBackendStepInput input) throws QException
   {
      String given = input.getValueString("storageBucketAlias");
      if(given != null && !given.isBlank())
      {
         return (given);
      }
      List<QRecord> plans = new BackupCatalog().query(BackupsTables.PLAN, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter(
         new com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria("isActive", com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator.EQUALS, true))
         .withOrderBy(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy("id", false)));
      return (plans.isEmpty() ? null : plans.get(0).getValueString("storageBucketAlias"));
   }



   /*******************************************************************************
    ** Review: what is selected and what will be left out.
    *******************************************************************************/
   public static class Transform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine records   = line(Status.OK, "record will be backed up", "records will be backed up", "record was backed up", "records were backed up");
      private final ProcessSummaryLine sensitive = line(Status.INFO, "field is sensitive and will be left out of the backup (including it needs backups.admin)",
         "fields are sensitive and will be left out of the backup (including them needs backups.admin)", "field was left out as sensitive", "fields were left out as sensitive");
      private final ProcessSummaryLine included  = line(Status.WARNING, "field is sensitive and will be written in the clear, because you have backups.admin",
         "fields are sensitive and will be written in the clear, because you have backups.admin", "field was written in the clear", "fields were written in the clear");
      private final ProcessSummaryLine noBucket  = line(Status.ERROR, "export has no destination: there is no active backup plan to take its bucket from",
         "exports have no destination: there is no active backup plan to take their bucket from", "export had no destination", "exports had no destination");
      private final ProcessSummaryLine denied    = line(Status.ERROR, "table cannot be read by you, so it cannot be backed up", "tables cannot be read by you, so they cannot be backed up",
         "table could not be read by you", "tables could not be read by you");
      private boolean described = false;



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String table = input.getTableName();
         if(!described)
         {
            described = true;
            if(!PermissionsHelper.hasTablePermission(input, table, TablePermissionSubType.READ))
            {
               denied.incrementCount();
            }
            if(bucketAlias(input) == null)
            {
               noBucket.incrementCount();
            }
            QTableMetaData meta  = QContext.getQInstance().getTable(table);
            int            count = 0;
            for(QFieldMetaData field : meta.getFields().values())
            {
               if(!field.getName().equals(meta.getPrimaryKeyField()) && (field.getIsSensitive() || field.getType() == QFieldType.PASSWORD))
               {
                  count++;
               }
            }
            (BackupsPermissionChecker.has(BackupsPermissions.ADMIN) ? included : sensitive).incrementCount(count);
         }
         if(denied.getCount() > 0)
         {
            return;
         }
         records.incrementCount(input.getRecords().size());
         output.getRecords().addAll(input.getRecords());
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         records.addSelfToListIfAnyCount(lines);
         sensitive.addSelfToListIfAnyCount(lines);
         included.addSelfToListIfAnyCount(lines);
         noBucket.addSelfToListIfAnyCount(lines);
         denied.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Result: collect the selected records, then run the export through the engine when
    ** the Result lines are asked for (after the last page, before post run).
    *******************************************************************************/
   public static class Load extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine succeeded = line(Status.OK, "export succeeded", "exports succeeded", "export succeeded", "exports succeeded");
      private final ProcessSummaryLine warnings  = line(Status.WARNING, "export finished with warnings", "exports finished with warnings", "export finished with warnings", "exports finished with warnings");
      private final ProcessSummaryLine failed    = line(Status.ERROR, "export failed", "exports failed", "export failed", "exports failed");
      private final ProcessSummaryLine rows      = line(Status.INFO, "record was written", "records were written", "record was written", "records were written");
      private final ProcessSummaryLine files     = line(Status.INFO, "referenced file was copied or already stored", "referenced files were copied or already stored",
         "referenced file was copied or already stored", "referenced files were copied or already stored");

      private final List<QRecord> buffered = new ArrayList<>();

      private String  tableName;
      private String  bucket;
      private boolean exported = false;



      /*******************************************************************************
       ** Remember what is being exported.
       *******************************************************************************/
      @Override
      public void preRun(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         super.preRun(input, output);
         tableName = input.getTableName();
         bucket = bucketAlias(input);
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         buffered.addAll(input.getRecords());
         output.getRecords().addAll(input.getRecords());
      }



      /*******************************************************************************
       ** All records are in (the summary is asked for once the last page is loaded, and
       ** before post run): back them up, once.
       *******************************************************************************/
      private void export() throws QException
      {
         if(exported)
         {
            return;
         }
         exported = true;
         if(bucket == null)
         {
            failed.incrementCount();
            failed.setMessageSuffix(": there is no active backup plan to take a bucket from");
            return;
         }
         BackupRunResult result = new BackupRunCoordinator().runAdHoc(request(tableName, bucket, buffered));
         switch(result.getStatus())
         {
            case "SUCCEEDED" -> succeeded.incrementCountAndAddPrimaryKey(result.getRunId());
            case "SUCCEEDED_WITH_WARNINGS" -> warnings.incrementCountAndAddPrimaryKey(result.getRunId());
            default ->
            {
               failed.incrementCountAndAddPrimaryKey(result.getRunId());
               failed.setMessageSuffix(result.getErrorMessage() == null ? null : ": " + result.getErrorMessage());
            }
         }
         rows.incrementCount((int) Math.min(Integer.MAX_VALUE, result.getRows()));
         files.incrementCount(result.getFilesCopied() + result.getFilesDeduplicated());
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         try
         {
            export();
         }
         catch(QException e)
         {
            failed.incrementCount();
            failed.setMessageSuffix(": " + e.getMessage());
         }
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         succeeded.addSelfToListIfAnyCount(lines);
         warnings.addSelfToListIfAnyCount(lines);
         failed.addSelfToListIfAnyCount(lines);
         rows.addSelfToListIfAnyCount(lines);
         files.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** The ad-hoc request for the selected records of a table.
    *******************************************************************************/
   public static BackupRunRequest request(String tableName, String bucketAlias, List<QRecord> selected)
   {
      QInstance      qInstance = QContext.getQInstance();
      QTableMetaData table     = qInstance.getTable(tableName);
      String         pk        = table.getPrimaryKeyField();
      QSession       session   = QContext.getQSession();
      String         user      = session == null || session.getUser() == null ? "system" : session.getUser().getIdReference();
      BackupsQBitConfig config = BackupsRuntime.getConfig();

      Map<String, QRecord> unique = new LinkedHashMap<>();
      for(QRecord record : selected)
      {
         unique.put(String.valueOf(record.getValue(pk)), record);
      }
      List<QRecord> inPkOrder = new ArrayList<>(unique.values());
      inPkOrder.sort(Comparator.comparing(r -> (Comparable) r.getValue(pk)));

      PlanSettings settings = PlanSettings.adHoc().with("planKey", "adhoc").with("storageBucketAlias", bucketAlias).with("sensitiveFieldPolicy", sensitivePolicy());
      return (new BackupRunRequest()
         .withSettings(settings)
         .withScope(new ScopeResolver.ResolvedScope(List.of(new ScopeResolver.ResolvedTable(tableName, TableRoles.roleOf(qInstance, table), null, List.of())), List.of(), List.of()))
         .withTrigger("MANUAL")
         .withRequestedBy(user)
         .withSecurityScope("USER")
         .withBucketAlias(bucketAlias)
         .withStorage(BackupsRuntime.storageFor(bucketAlias))
         .withSourceResolver(BackupsRuntime::storageFor)
         .withInstanceName(config.getInstanceName() != null ? config.getInstanceName() : "qqq")
         .withPlanName("Export of " + table.getLabel())
         .withReader(new BufferedReader(inPkOrder))
         .withClock(BackupsRuntime.getClock()));
   }



   /*******************************************************************************
    ** Serves the selected records to the engine, in primary key order.
    *******************************************************************************/
   static class BufferedReader implements TableReader
   {
      private final List<QRecord> records;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      BufferedReader(List<QRecord> records)
      {
         this.records = records;
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void read(QTableMetaData table, String filterJson, Serializable startAfterKey, int pageSize, QBackendTransaction transaction, java.util.function.Consumer<QRecord> sink)
      {
         records.forEach(sink);
      }
   }
}
