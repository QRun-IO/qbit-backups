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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.permissions.BackupsPermissions;
import com.kingsrook.qbits.backups.restore.CatalogImporter;
import com.kingsrook.qbits.backups.restore.RestoreSummaries;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qqq.backend.core.actions.audits.AuditAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.exceptions.QUserFacingException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepOutput;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QComponentType;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendComponentMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QFrontendStepMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.processes.QProcessMetaData;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractExtractStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractLoadStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.AbstractTransformStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ExtractViaQueryStep;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.ProcessSummaryProviderInterface;
import com.kingsrook.qqq.backend.core.processes.implementations.etl.streamedwithfrontend.StreamedETLWithFrontendProcess;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** The catalog and download processes (spec 9.6 and 9.7):  Import backups from
 ** storage (a table action on backups_run) and Download (a record action on a run
 ** or an artifact).  Standard streamed ETL, Review and Result summary lines.
 *******************************************************************************/
public final class CatalogProcesses
{
   public static final String IMPORT_CATALOG     = "backupImportCatalog";
   public static final String DOWNLOAD           = "backupDownload";
   public static final String DOWNLOAD_ARTIFACT  = "backupDownloadArtifact";

   public static final Duration LINK_TTL = Duration.ofMinutes(15);

   private static final QLogger LOG = QLogger.getLogger(CatalogProcesses.class);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private CatalogProcesses()
   {
   }



   /*******************************************************************************
    ** The processes defined here.
    *******************************************************************************/
   public static List<QProcessMetaData> processes()
   {
      List<QProcessMetaData> processes = new ArrayList<>();

      QProcessMetaData importProcess = definition(IMPORT_CATALOG, "Import backups from storage", BackupsTables.RUN, ImportExtract.class, ImportTransform.class, ImportLoad.class,
         "These are the backups found in storage that are not in the catalog yet. Nothing is written until you continue.");
      importProcess.withStep(0, new QFrontendStepMetaData()
         .withName("input")
         .withLabel("Where are the backups?")
         .withComponent(new QFrontendComponentMetaData().withType(QComponentType.EDIT_FORM))
         .withFormField(new QFieldMetaData("bucketAlias", QFieldType.STRING).withLabel("Backup bucket alias").withIsRequired(true))
         .withFormField(new QFieldMetaData("prefix", QFieldType.STRING).withLabel("Folder (prefix)").withDefaultValue("qqq-backups/")));
      importProcess.setMinInputRecords(0);
      processes.add(BackupsProcesses.permission(importProcess, BackupsPermissions.ADMIN));

      processes.add(BackupsProcesses.permission(definition(DOWNLOAD, "Download", BackupsTables.RUN, ExtractViaQueryStep.class, DownloadTransform.class, DownloadLoad.class,
         "These are the files you will get download links for. Nothing is changed."), BackupsPermissions.RESTORE));
      processes.add(BackupsProcesses.permission(definition(DOWNLOAD_ARTIFACT, "Download", BackupsTables.ARTIFACT, ExtractViaQueryStep.class, DownloadTransform.class, DownloadLoad.class,
         "These are the files you will get download links for. Nothing is changed."), BackupsPermissions.RESTORE));
      return (processes);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static QProcessMetaData definition(String name, String label, String table, Class<? extends AbstractExtractStep> extract,
                                              Class<? extends AbstractTransformStep> transform, Class<? extends AbstractLoadStep> load, String preview)
   {
      Map<String, Serializable> values = new HashMap<>();
      values.put(StreamedETLWithFrontendProcess.FIELD_SOURCE_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_DESTINATION_TABLE, table);
      values.put(StreamedETLWithFrontendProcess.FIELD_SUPPORTS_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_DO_FULL_VALIDATION, true);
      values.put(StreamedETLWithFrontendProcess.FIELD_TRANSACTION_LEVEL, StreamedETLWithFrontendProcess.TRANSACTION_LEVEL_AUTO_COMMIT);
      values.put(StreamedETLWithFrontendProcess.FIELD_PREVIEW_MESSAGE, preview);
      QProcessMetaData process = StreamedETLWithFrontendProcess.defineProcessMetaData(extract, transform, load, values);
      process.setName(name);
      process.setLabel(label);
      process.setTableName(table);
      process.setPermissionRules(BackupsTables.permissionRules());
      return (process);
   }



   /*******************************************************************************
    ** Lists the manifests under the bucket alias and prefix; one record per manifest.
    *******************************************************************************/
   public static class ImportExtract extends AbstractExtractStep
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void run(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String alias = input.getValueString("bucketAlias");
         if(alias == null || alias.isBlank())
         {
            throw (new QUserFacingException("Choose the backup bucket alias to import from."));
         }
         BackupStorage storage = BackupsRuntime.storageFor(alias);
         for(String key : new CatalogImporter().findManifestKeys(storage, input.getValueString("prefix")))
         {
            getRecordPipe().addRecord(new QRecord().withTableName(BackupsTables.RUN).withValue("manifestKey", key).withValue("storageBucketAlias", alias));
         }
      }
   }



   /*******************************************************************************
    ** Review: what each manifest is (new, already in the catalog, or damaged).
    *******************************************************************************/
   public static class ImportTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine found    = RestoreSummaries.line(Status.INFO, 0, "backup was found in storage", "backups were found in storage", "backup was found in storage", "backups were found in storage");
      private final ProcessSummaryLine newRuns  = RestoreSummaries.line(Status.OK, 0, "backup will be added to the catalog", "backups will be added to the catalog", "backup was added to the catalog", "backups were added to the catalog");
      private final ProcessSummaryLine existing = RestoreSummaries.line(Status.INFO, 0, "backup is already in the catalog (skipped)", "backups are already in the catalog (skipped)", "backup was already in the catalog", "backups were already in the catalog");
      private final ProcessSummaryLine invalid  = RestoreSummaries.line(Status.ERROR, 0, "backup has a manifest or checksum problem and will be skipped", "backups have a manifest or checksum problem and will be skipped",
         "backup had a manifest or checksum problem and was skipped", "backups had a manifest or checksum problem and were skipped");
      private final ArrayList<String>  problems = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(QRecord record : input.getRecords())
         {
            BackupStorage storage = BackupsRuntime.storageFor(record.getValueString("storageBucketAlias"));
            CatalogImporter.Inspection inspection = new CatalogImporter().inspect(storage, record.getValueString("manifestKey"));
            found.incrementCount();
            switch(inspection.status())
            {
               case CatalogImporter.Inspection.NEW ->
               {
                  newRuns.incrementCount();
                  output.getRecords().add(record);
               }
               case CatalogImporter.Inspection.EXISTS -> existing.incrementCount();
               default ->
               {
                  invalid.incrementCount();
                  for(String problem : inspection.problems())
                  {
                     problems.add(inspection.manifestKey() + ": " + problem);
                  }
               }
            }
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         found.addSelfToListIfAnyCount(lines);
         newRuns.addSelfToListIfAnyCount(lines);
         existing.addSelfToListIfAnyCount(lines);
         invalid.setBulletsOfText(problems);
         invalid.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** Execute: create the run and artifact rows from each new manifest.
    *******************************************************************************/
   public static class ImportLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine imported = RestoreSummaries.line(Status.OK, 0, "backup will be added to the catalog", "backups will be added to the catalog", "backup was added to the catalog", "backups were added to the catalog");
      private final ProcessSummaryLine skipped  = RestoreSummaries.line(Status.WARNING, 0, "backup was not added", "backups were not added", "backup was not added", "backups were not added");



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         CatalogImporter importer = new CatalogImporter();
         for(QRecord record : input.getRecords())
         {
            String        alias   = record.getValueString("storageBucketAlias");
            BackupStorage storage = BackupsRuntime.storageFor(alias);
            CatalogImporter.Inspection inspection = importer.inspect(storage, record.getValueString("manifestKey"));
            if(CatalogImporter.Inspection.NEW.equals(inspection.status()))
            {
               imported.incrementCountAndAddPrimaryKey(importer.importRun(alias, storage, inspection));
            }
            else
            {
               skipped.incrementCount();
            }
            output.getRecords().add(record);
         }
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         imported.addSelfToListIfAnyCount(lines);
         skipped.addSelfToListIfAnyCount(lines);
         return (lines);
      }
   }



   /*******************************************************************************
    ** One object to link to.
    *******************************************************************************/
   record DownloadItem(String auditTable, Integer auditId, BackupStorage storage, String key, String downloadName, boolean encrypted, long size)
   {
   }



   /*******************************************************************************
    ** The objects the selected records ask for: for a run, all of its artifacts; for
    ** an artifact, itself plus the manifest and checksums of its run.
    *******************************************************************************/
   static List<DownloadItem> items(List<QRecord> records) throws QException
   {
      BackupCatalog catalog = new BackupCatalog();
      Map<String, DownloadItem> items = new LinkedHashMap<>();
      for(QRecord record : records)
      {
         boolean   isArtifact = BackupsTables.ARTIFACT.equals(record.getTableName()) || record.getValue("artifactType") != null;
         Integer   runId      = isArtifact ? record.getValueInteger("backupRunId") : record.getValueInteger("id");
         QRecord   run        = catalog.get(BackupsTables.RUN, runId).orElseThrow(() -> new QUserFacingException("The backup run was not found."));
         String    table      = isArtifact ? BackupsTables.ARTIFACT : BackupsTables.RUN;
         BackupStorage storage = BackupsRuntime.storageFor(run.getValueString("storageBucketAlias"));

         List<QRecord> artifacts = new ArrayList<>();
         if(isArtifact)
         {
            artifacts.add(record);
            for(QRecord other : catalog.where(BackupsTables.ARTIFACT, "backupRunId", runId))
            {
               if("MANIFEST".equals(other.getValueString("artifactType")) || "CHECKSUMS".equals(other.getValueString("artifactType")))
               {
                  artifacts.add(other);
               }
            }
         }
         else
         {
            artifacts.addAll(catalog.where(BackupsTables.ARTIFACT, "backupRunId", runId));
         }

         for(QRecord artifact : artifacts)
         {
            if(!"COMPLETE".equals(artifact.getValueString("status")))
            {
               continue;
            }
            String key = run.getValueString("runPrefix") + artifact.getValueString("objectKey");
            String name = run.getValueString("runKey") + "-" + artifact.getValueString("objectKey").replace('/', '-');
            items.putIfAbsent(key, new DownloadItem(table, isArtifact && artifact == record ? artifact.getValueInteger("id") : runId, storage, key, name,
               Boolean.TRUE.equals(artifact.getValueBoolean("encrypted")), artifact.getValueLong("bytesStored") == null ? 0 : artifact.getValueLong("bytesStored")));
         }
      }
      return (new ArrayList<>(items.values()));
   }



   /*******************************************************************************
    ** Review: what will be linked.
    *******************************************************************************/
   public static class DownloadTransform extends AbstractTransformStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine objects   = RestoreSummaries.line(Status.OK, 0, "file will get a download link", "files will get download links", "file got a download link", "files got download links");
      private final ProcessSummaryLine encrypted = RestoreSummaries.line(Status.INFO, 0, "file is encrypted: you get the ciphertext only, and decrypt it with the command line tool",
         "files are encrypted: you get the ciphertext only, and decrypt them with the command line tool", "file is encrypted (ciphertext only)", "files are encrypted (ciphertext only)");
      private long bytes;



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         for(DownloadItem item : items(input.getRecords()))
         {
            objects.incrementCount();
            bytes += item.size();
            if(item.encrypted())
            {
               encrypted.incrementCount();
            }
         }
         output.getRecords().addAll(input.getRecords());
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         objects.addSelfToListIfAnyCount(lines);
         encrypted.addSelfToListIfAnyCount(lines);
         if(objects.getCount() != null && objects.getCount() > 0)
         {
            lines.add(RestoreSummaries.message(Status.INFO, "The links last " + LINK_TTL.toMinutes() + " minutes and the download is about " + bytes + " bytes. Each link is recorded in the audit."));
         }
         return (lines);
      }
   }



   /*******************************************************************************
    ** Execute: issue the presigned links (ciphertext only), each one audited.
    *******************************************************************************/
   public static class DownloadLoad extends AbstractLoadStep implements ProcessSummaryProviderInterface
   {
      private final ProcessSummaryLine issued = RestoreSummaries.line(Status.OK, 0, "download link was issued", "download links were issued", "download link was issued", "download links were issued");
      private final ArrayList<String>  links  = new ArrayList<>();



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public void runOnePage(RunBackendStepInput input, RunBackendStepOutput output) throws QException
      {
         String user = QContext.getQSession() == null || QContext.getQSession().getUser() == null ? "system" : QContext.getQSession().getUser().getIdReference();
         for(DownloadItem item : items(input.getRecords()))
         {
            Instant expires = BackupsRuntime.getClock().instant().plus(LINK_TTL);
            String  url     = item.storage().presignGet(item.key(), LINK_TTL, item.downloadName());
            links.add(item.downloadName() + (item.encrypted() ? " (encrypted)" : "") + ": " + url);
            issued.incrementCount();
            LOG.info("Issued a backup download link", logPair("object", item.key()), logPair("user", user), logPair("expires", expires));
            try
            {
               AuditAction.execute(item.auditTable(), item.auditId(), Map.of(), "Download link issued for " + item.key() + " to " + user + ", expires " + expires);
            }
            catch(RuntimeException e)
            {
               LOG.warn("Could not audit a download link", e, logPair("object", item.key()));
            }
         }
         output.getRecords().addAll(input.getRecords());
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public ArrayList<ProcessSummaryLineInterface> getProcessSummary(RunBackendStepOutput output, boolean isForResultScreen)
      {
         ArrayList<ProcessSummaryLineInterface> lines = new ArrayList<>();
         issued.setBulletsOfText(links);
         issued.addSelfToListIfAnyCount(lines);
         if(issued.getCount() != null && issued.getCount() > 0)
         {
            lines.add(RestoreSummaries.message(Status.INFO, "The links expire in " + LINK_TTL.toMinutes() + " minutes. Encrypted files download as ciphertext: use the command line tool to decrypt them."));
         }
         return (lines);
      }
   }
}
