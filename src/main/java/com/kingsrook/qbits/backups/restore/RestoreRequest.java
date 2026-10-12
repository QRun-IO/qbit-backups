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


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.files.FileReferenceProvider;
import com.kingsrook.qqq.backend.core.model.actions.processes.RunBackendStepInput;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** Everything a restore needs to know (spec 9.3): the source run, the tables and
 ** per-table filters, the target, the mode, how rows are matched, what happens to
 ** ids, and the options.  Built from the first screen of the process.
 *******************************************************************************/
public class RestoreRequest
{
   public static final String MODE_INSERT_NEW = "INSERT_NEW";
   public static final String MODE_UPSERT     = "UPSERT";
   public static final String MODE_REPLACE    = "REPLACE";

   public static final String IDS_PRESERVE = "PRESERVE";
   public static final String IDS_REMAP    = "REMAP_NEW_IDS";

   public static final String MATCH_PRIMARY_KEY = "PRIMARY_KEY";

   private Integer             sourceRunId;
   private List<String>        tables             = new ArrayList<>();
   private Map<String, String> filters            = new LinkedHashMap<>();
   private String              targetTable;
   private String              mode               = MODE_INSERT_NEW;
   private String              matchOn            = MATCH_PRIMARY_KEY;
   private String              idStrategy         = IDS_PRESERVE;
   private boolean             suppressAutomations = true;
   private boolean             publishEvents      = false;
   private boolean             runCustomizers     = false;
   private boolean             perRecordAudit     = false;
   private boolean             restoreFiles       = true;
   private boolean             preRestoreBackup   = true;
   private String              fileTargetBucketAlias;
   private String              fileTargetPrefix;
   private String              confirmationText;
   private String              requestedBy        = "system";
   private int                 pageSize           = 1000;
   private List<FileReferenceProvider> fileProviders = new ArrayList<>(List.of(new FileReferenceProvider.FromTableMetaData()));
   private RestoreWriter       writer             = new StandardRestoreWriter();



   /*******************************************************************************
    ** Build from the values of the first screen of the process.  The tables value is
    ** a comma separated list (blank means every table in the backup); the filters
    ** value is a JSON object of table name to query filter.
    *******************************************************************************/
   public static RestoreRequest fromInput(RunBackendStepInput input, Integer runId, List<String> forcedTables) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      RestoreRequest request = new RestoreRequest();
      request.sourceRunId = runId;
      if(forcedTables != null && !forcedTables.isEmpty())
      {
         request.tables = new ArrayList<>(forcedTables);
      }
      else
      {
         request.tables = splitList(input.getValueString("tables"));
      }
      request.filters = parseFilters(input.getValueString("tableFilters"));
      request.targetTable = blankToNull(input.getValueString("targetTable"));
      request.mode = orDefault(input.getValueString("mode"), MODE_INSERT_NEW);
      request.matchOn = orDefault(input.getValueString("matchOn"), MATCH_PRIMARY_KEY);
      request.idStrategy = orDefault(input.getValueString("idStrategy"), IDS_PRESERVE);
      request.suppressAutomations = bool(input, "suppressAutomations", true);
      request.publishEvents = bool(input, "publishEvents", false);
      request.runCustomizers = bool(input, "runCustomizers", false);
      request.perRecordAudit = bool(input, "perRecordAudit", false);
      request.restoreFiles = bool(input, "restoreFiles", true);
      request.preRestoreBackup = bool(input, "preRestoreBackup", true);
      request.fileTargetBucketAlias = blankToNull(input.getValueString("fileTargetBucketAlias"));
      request.fileTargetPrefix = blankToNull(input.getValueString("fileTargetPrefix"));
      request.confirmationText = input.getValueString("confirmationText");
      com.kingsrook.qqq.backend.core.model.session.QSession session = com.kingsrook.qqq.backend.core.context.QContext.getQSession();
      request.requestedBy = session == null || session.getUser() == null ? "system" : session.getUser().getIdReference();
      return (request);
   }



   /*******************************************************************************
    ** The text the user must type to confirm a REPLACE of these tables.
    *******************************************************************************/
   public static String expectedConfirmation(List<String> tables)
   {
      return (tables.size() == 1 ? tables.get(0) : "REPLACE " + tables.size() + " TABLES");
   }



   /*******************************************************************************
    ** The filter json for a table, or null.
    *******************************************************************************/
   public String filterFor(String table)
   {
      return (filters.get(table));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static List<String> splitList(String text)
   {
      List<String> rs = new ArrayList<>();
      if(StringUtils.hasContent(text))
      {
         for(String part : text.split(","))
         {
            if(!part.isBlank())
            {
               rs.add(part.trim());
            }
         }
      }
      return (rs);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Map<String, String> parseFilters(String json) throws com.kingsrook.qqq.backend.core.exceptions.QException
   {
      Map<String, String> rs = new LinkedHashMap<>();
      if(!StringUtils.hasContent(json))
      {
         return (rs);
      }
      try
      {
         com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
         for(var it = node.fields(); it.hasNext(); )
         {
            var entry = it.next();
            rs.put(entry.getKey(), entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue().toString());
         }
      }
      catch(java.io.IOException e)
      {
         throw (new com.kingsrook.qqq.backend.core.exceptions.QException("The per-table filters are not valid JSON: " + e.getMessage()));
      }
      return (rs);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean bool(RunBackendStepInput input, String name, boolean defaultValue)
   {
      Boolean value = input.getValueBoolean(name);
      return (value == null ? defaultValue : value);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String orDefault(String value, String defaultValue)
   {
      return (StringUtils.hasContent(value) ? value : defaultValue);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String blankToNull(String value)
   {
      return (StringUtils.hasContent(value) ? value : null);
   }



   /*******************************************************************************
    ** Getter for sourceRunId
    *******************************************************************************/
   public Integer getSourceRunId()
   {
      return (sourceRunId);
   }



   /*******************************************************************************
    ** Fluent setter for sourceRunId
    *******************************************************************************/
   public RestoreRequest withSourceRunId(Integer sourceRunId)
   {
      this.sourceRunId = sourceRunId;
      return (this);
   }



   /*******************************************************************************
    ** Getter for tables (empty means every table in the backup)
    *******************************************************************************/
   public List<String> getTables()
   {
      return (tables);
   }



   /*******************************************************************************
    ** Fluent setter for tables
    *******************************************************************************/
   public RestoreRequest withTables(List<String> tables)
   {
      this.tables = new ArrayList<>(tables);
      return (this);
   }



   /*******************************************************************************
    ** Getter for filters
    *******************************************************************************/
   public Map<String, String> getFilters()
   {
      return (filters);
   }



   /*******************************************************************************
    ** Fluent setter for one table filter (json of a QQueryFilter)
    *******************************************************************************/
   public RestoreRequest withFilter(String table, String filterJson)
   {
      this.filters.put(table, filterJson);
      return (this);
   }



   /*******************************************************************************
    ** Getter for targetTable
    *******************************************************************************/
   public String getTargetTable()
   {
      return (targetTable);
   }



   /*******************************************************************************
    ** Fluent setter for targetTable
    *******************************************************************************/
   public RestoreRequest withTargetTable(String targetTable)
   {
      this.targetTable = targetTable;
      return (this);
   }



   /*******************************************************************************
    ** Getter for mode
    *******************************************************************************/
   public String getMode()
   {
      return (mode);
   }



   /*******************************************************************************
    ** Fluent setter for mode
    *******************************************************************************/
   public RestoreRequest withMode(String mode)
   {
      this.mode = mode;
      return (this);
   }



   /*******************************************************************************
    ** Getter for matchOn
    *******************************************************************************/
   public String getMatchOn()
   {
      return (matchOn);
   }



   /*******************************************************************************
    ** Fluent setter for matchOn
    *******************************************************************************/
   public RestoreRequest withMatchOn(String matchOn)
   {
      this.matchOn = matchOn;
      return (this);
   }



   /*******************************************************************************
    ** Getter for idStrategy
    *******************************************************************************/
   public String getIdStrategy()
   {
      return (idStrategy);
   }



   /*******************************************************************************
    ** Fluent setter for idStrategy
    *******************************************************************************/
   public RestoreRequest withIdStrategy(String idStrategy)
   {
      this.idStrategy = idStrategy;
      return (this);
   }



   /*******************************************************************************
    ** Getter for suppressAutomations
    *******************************************************************************/
   public boolean getSuppressAutomations()
   {
      return (suppressAutomations);
   }



   /*******************************************************************************
    ** Fluent setter for suppressAutomations
    *******************************************************************************/
   public RestoreRequest withSuppressAutomations(boolean suppressAutomations)
   {
      this.suppressAutomations = suppressAutomations;
      return (this);
   }



   /*******************************************************************************
    ** Getter for publishEvents
    *******************************************************************************/
   public boolean getPublishEvents()
   {
      return (publishEvents);
   }



   /*******************************************************************************
    ** Fluent setter for publishEvents
    *******************************************************************************/
   public RestoreRequest withPublishEvents(boolean publishEvents)
   {
      this.publishEvents = publishEvents;
      return (this);
   }



   /*******************************************************************************
    ** Getter for runCustomizers
    *******************************************************************************/
   public boolean getRunCustomizers()
   {
      return (runCustomizers);
   }



   /*******************************************************************************
    ** Fluent setter for runCustomizers
    *******************************************************************************/
   public RestoreRequest withRunCustomizers(boolean runCustomizers)
   {
      this.runCustomizers = runCustomizers;
      return (this);
   }



   /*******************************************************************************
    ** Getter for perRecordAudit
    *******************************************************************************/
   public boolean getPerRecordAudit()
   {
      return (perRecordAudit);
   }



   /*******************************************************************************
    ** Fluent setter for perRecordAudit
    *******************************************************************************/
   public RestoreRequest withPerRecordAudit(boolean perRecordAudit)
   {
      this.perRecordAudit = perRecordAudit;
      return (this);
   }



   /*******************************************************************************
    ** Getter for restoreFiles
    *******************************************************************************/
   public boolean getRestoreFiles()
   {
      return (restoreFiles);
   }



   /*******************************************************************************
    ** Fluent setter for restoreFiles
    *******************************************************************************/
   public RestoreRequest withRestoreFiles(boolean restoreFiles)
   {
      this.restoreFiles = restoreFiles;
      return (this);
   }



   /*******************************************************************************
    ** Getter for preRestoreBackup
    *******************************************************************************/
   public boolean getPreRestoreBackup()
   {
      return (preRestoreBackup);
   }



   /*******************************************************************************
    ** Fluent setter for preRestoreBackup
    *******************************************************************************/
   public RestoreRequest withPreRestoreBackup(boolean preRestoreBackup)
   {
      this.preRestoreBackup = preRestoreBackup;
      return (this);
   }



   /*******************************************************************************
    ** Getter for fileTargetBucketAlias
    *******************************************************************************/
   public String getFileTargetBucketAlias()
   {
      return (fileTargetBucketAlias);
   }



   /*******************************************************************************
    ** Fluent setter for fileTargetBucketAlias
    *******************************************************************************/
   public RestoreRequest withFileTargetBucketAlias(String fileTargetBucketAlias)
   {
      this.fileTargetBucketAlias = fileTargetBucketAlias;
      return (this);
   }



   /*******************************************************************************
    ** Getter for fileTargetPrefix
    *******************************************************************************/
   public String getFileTargetPrefix()
   {
      return (fileTargetPrefix);
   }



   /*******************************************************************************
    ** Fluent setter for fileTargetPrefix
    *******************************************************************************/
   public RestoreRequest withFileTargetPrefix(String fileTargetPrefix)
   {
      this.fileTargetPrefix = fileTargetPrefix;
      return (this);
   }



   /*******************************************************************************
    ** Getter for confirmationText
    *******************************************************************************/
   public String getConfirmationText()
   {
      return (confirmationText);
   }



   /*******************************************************************************
    ** Fluent setter for confirmationText
    *******************************************************************************/
   public RestoreRequest withConfirmationText(String confirmationText)
   {
      this.confirmationText = confirmationText;
      return (this);
   }



   /*******************************************************************************
    ** Getter for requestedBy
    *******************************************************************************/
   public String getRequestedBy()
   {
      return (requestedBy);
   }



   /*******************************************************************************
    ** Fluent setter for requestedBy
    *******************************************************************************/
   public RestoreRequest withRequestedBy(String requestedBy)
   {
      this.requestedBy = requestedBy;
      return (this);
   }



   /*******************************************************************************
    ** Getter for pageSize
    *******************************************************************************/
   public int getPageSize()
   {
      return (pageSize);
   }



   /*******************************************************************************
    ** Fluent setter for pageSize
    *******************************************************************************/
   public RestoreRequest withPageSize(int pageSize)
   {
      this.pageSize = pageSize;
      return (this);
   }



   /*******************************************************************************
    ** Getter for fileProviders
    *******************************************************************************/
   public List<FileReferenceProvider> getFileProviders()
   {
      return (fileProviders);
   }



   /*******************************************************************************
    ** Getter for writer
    *******************************************************************************/
   public RestoreWriter getWriter()
   {
      return (writer);
   }



   /*******************************************************************************
    ** Fluent setter for writer (tests, or a backend specific writer)
    *******************************************************************************/
   public RestoreRequest withWriter(RestoreWriter writer)
   {
      this.writer = writer;
      return (this);
   }
}
