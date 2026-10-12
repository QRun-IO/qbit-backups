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

package com.kingsrook.qbits.backups.model;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.permissions.BackupsPermissionChecker;
import com.kingsrook.qbits.backups.plan.BackupPlanTableCustomizer;
import com.kingsrook.qbits.backups.schedule.BackupPlanSyncToScheduledJobProcess;
import com.kingsrook.qbits.backups.schedule.BackupPlanVerifySyncToScheduledJobProcess;
import com.kingsrook.qqq.backend.core.actions.customizers.MultiCustomizer;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizers;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReferenceWithProperties;
import com.kingsrook.qqq.backend.core.scheduler.processes.BaseSyncToScheduledJobTableCustomizer;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.ChildRecordListRenderer;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QFieldSection;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Tier;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValue;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSourceType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Association;
import com.kingsrook.qqq.backend.core.utils.StringUtils;


/*******************************************************************************
 ** The qbit tables (design spec section 4).  All are standard visible QQQ
 ** tables, prefixed backups_, in lowercase snake_case.  Tables written by
 ** processes are read-only in the UI.  No table ever holds a key, password or
 ** credential: only references such as a key name or a bucket alias.
 *******************************************************************************/
public final class BackupsTables
{
   public static final String PLAN              = "backups_plan";
   public static final String PLAN_SCOPE        = "backups_plan_scope";
   public static final String RUN               = "backups_run";
   public static final String ARTIFACT          = "backups_artifact";
   public static final String VERIFICATION      = "backups_verification";
   public static final String RESTORE_RUN       = "backups_restore_run";
   public static final String RESTORE_RUN_TABLE = "backups_restore_run_table";
   public static final String FILE_OBJECT       = "backups_file_object";
   public static final String ID_MAP            = "backups_id_map";

   /** Tables that only processes write (read-only in the UI; no delete). */
   public static final List<String> PROCESS_WRITTEN = List.of(RUN, ARTIFACT, VERIFICATION, RESTORE_RUN, RESTORE_RUN_TABLE, FILE_OBJECT, ID_MAP);

   /** The tables shown in the nav (sub-tables appear as relations on their parents). */
   public static final List<String> NAV_TABLES = List.of(PLAN, RUN, RESTORE_RUN, VERIFICATION);

   public static final List<String> ALL = List.of(PLAN, PLAN_SCOPE, RUN, ARTIFACT, VERIFICATION, RESTORE_RUN, RESTORE_RUN_TABLE, FILE_OBJECT, ID_MAP);

   public static final String PVS_PREFIX = "backups_";

   private static final Map<String, List<String>> ENUMS = new LinkedHashMap<>();

   static
   {
      ENUMS.put("scope_type", List.of("INSTANCE", "APPS", "QBITS", "TABLES"));
      ENUMS.put("table_role", List.of("DATA", "AUDIT", "DERIVED", "EPHEMERAL", "SECRET", "BACKUPS", "EXTERNAL"));
      ENUMS.put("scope_action", List.of("INCLUDE", "EXCLUDE"));
      ENUMS.put("selector_type", List.of("TABLE", "APP", "QBIT", "ROLE"));
      ENUMS.put("format", List.of("JSONL", "CSV", "JSONL_AND_CSV", "PARQUET"));
      ENUMS.put("compression", List.of("GZIP", "ZSTD", "NONE"));
      ENUMS.put("encryption_mode", List.of("NONE", "CLIENT_AES256_GCM"));
      ENUMS.put("sensitive_policy", List.of("EXCLUDE", "INCLUDE_ENCRYPTED_ONLY", "INCLUDE"));
      ENUMS.put("heavy_policy", List.of("INLINE", "SEPARATE_OBJECTS", "EXCLUDE"));
      ENUMS.put("file_policy", List.of("COPY", "REFERENCE_ONLY"));
      ENUMS.put("file_missing_policy", List.of("WARN", "FAIL"));
      ENUMS.put("consistency", List.of("SNAPSHOT_REQUIRED", "SNAPSHOT_PREFERRED", "PER_TABLE"));
      ENUMS.put("consistency_achieved", List.of("INSTANCE_SNAPSHOT", "BACKEND_SNAPSHOT", "PER_TABLE", "BEST_EFFORT"));
      ENUMS.put("incremental_mode", List.of("FULL", "CHANGED_SINCE_LAST"));
      ENUMS.put("native_dump", List.of("NONE", "PG_DUMP_CUSTOM"));
      ENUMS.put("lock_mode", List.of("NONE", "GOVERNANCE", "COMPLIANCE"));
      ENUMS.put("sla_status", List.of("OK", "LATE", "FAILING", "MISSING_IN_STORAGE", "UNVERIFIED", "ABANDONED", "OWNER_INVALID", "UNKNOWN"));
      ENUMS.put("run_trigger", List.of("SCHEDULED", "MANUAL", "PRE_RESTORE", "RESUME", "IMPORTED"));
      ENUMS.put("run_status", List.of("QUEUED", "RUNNING", "SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "FAILED", "TIMED_OUT", "CANCELLING", "CANCELLED", "ABANDONED"));
      ENUMS.put("retention_class", List.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY", "NONE"));
      ENUMS.put("verification_status", List.of("UNVERIFIED", "STORAGE_OK", "CONTENT_OK", "TEST_RESTORE_OK", "FAILED"));
      ENUMS.put("artifact_type", List.of("TABLE_DATA", "TABLE_BLOBS", "FILE_OBJECTS_INDEX", "NATIVE_DUMP", "MANIFEST", "CHECKSUMS"));
      ENUMS.put("artifact_status", List.of("WRITING", "COMPLETE", "FAILED"));
      ENUMS.put("verify_level", List.of("STORAGE", "CONTENT", "TEST_RESTORE"));
      ENUMS.put("verify_result", List.of("PASSED", "PASSED_WITH_WARNINGS", "FAILED", "RUNNING", "ERROR"));
      ENUMS.put("restore_status", List.of("PREVIEWED", "QUEUED", "RUNNING", "SUCCEEDED", "SUCCEEDED_WITH_WARNINGS", "FAILED", "CANCELLING", "CANCELLED"));
      ENUMS.put("restore_mode", List.of("INSERT_NEW", "UPSERT", "REPLACE"));
      ENUMS.put("id_strategy", List.of("PRESERVE", "REMAP_NEW_IDS"));
      ENUMS.put("schema_status", List.of("MATCH", "COMPATIBLE", "MISMATCH"));
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsTables()
   {
   }



   /*******************************************************************************
    ** The name of the possible value source for an enum key.
    *******************************************************************************/
   public static String pvs(String enumKey)
   {
      return (PVS_PREFIX + enumKey);
   }



   /*******************************************************************************
    ** The values of an enum, by key.
    *******************************************************************************/
   public static List<String> enumValues(String enumKey)
   {
      return (ENUMS.get(enumKey));
   }



   /*******************************************************************************
    ** Permission rules shared by every qbit table, app, widget and process.
    *******************************************************************************/
   public static QPermissionRules permissionRules()
   {
      return (new QPermissionRules()
         .withLevel(PermissionLevel.HAS_ACCESS_PERMISSION)
         .withPermissionBaseName(BackupsQBitConfig.PERMISSION_BASE_NAME)
         .withCustomPermissionChecker(new QCodeReference(BackupsPermissionChecker.class)));
   }



   /*******************************************************************************
    ** The possible-value sources: enums and table references.
    *******************************************************************************/
   public static List<QPossibleValueSource> possibleValueSources()
   {
      List<QPossibleValueSource> rs = new ArrayList<>();
      for(Map.Entry<String, List<String>> entry : ENUMS.entrySet())
      {
         List<QPossibleValue<?>> values = new ArrayList<>();
         for(String value : entry.getValue())
         {
            values.add(new QPossibleValue<>(value, StringUtils.allCapsToMixedCase(value)));
         }
         rs.add(new QPossibleValueSource().withName(pvs(entry.getKey())).withType(QPossibleValueSourceType.ENUM).withEnumValues(values));
      }
      rs.add(QPossibleValueSource.newForTable(PLAN));
      rs.add(QPossibleValueSource.newForTable(RUN));
      rs.add(QPossibleValueSource.newForTable(RESTORE_RUN));
      return (rs);
   }



   /*******************************************************************************
    ** The joins that back the parent-to-child associations (child lists on parents).
    *******************************************************************************/
   public static List<QJoinMetaData> joins()
   {
      return (List.of(
         oneToMany("backupsPlanScopeJoinPlan", PLAN, PLAN_SCOPE, "backupPlanId"),
         oneToMany("backupsArtifactJoinRun", RUN, ARTIFACT, "backupRunId"),
         oneToMany("backupsVerificationJoinRun", RUN, VERIFICATION, "backupRunId"),
         oneToMany("backupsRestoreRunJoinRun", RUN, RESTORE_RUN, "sourceBackupRunId"),
         oneToMany("backupsRunJoinPlan", PLAN, RUN, "backupPlanId"),
         oneToMany("backupsRestoreRunTableJoinRestoreRun", RESTORE_RUN, RESTORE_RUN_TABLE, "backupRestoreRunId")));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static QJoinMetaData oneToMany(String name, String parent, String child, String childField)
   {
      return (new QJoinMetaData().withName(name).withType(JoinType.ONE_TO_MANY).withLeftTable(parent).withRightTable(child).withJoinOn(new JoinOn("id", childField)));
   }



   /*******************************************************************************
    ** All tables.
    *******************************************************************************/
   public static List<QTableMetaData> tables(BackupsQBitConfig config)
   {
      return (List.of(plan(config), planScope(config), run(config), artifact(config), verification(config),
         restoreRun(config), restoreRunTable(config), fileObject(config), idMap(config)).stream().map(BackupsTables::withSections).toList());
   }



   /*******************************************************************************
    ** Group a table's fields into record-screen sections (the identity and status
    ** first, then one section per concern, then the system fields), so a record reads
    ** as a few short groups instead of one long list.  Fields not named here land in
    ** the last section.
    *******************************************************************************/
   static QTableMetaData withSections(QTableMetaData table)
   {
      List<Object[]> layout = switch(table.getName())
      {
         case PLAN -> List.of(
            new Object[] { "plan", "Plan", "backup", Tier.T1, "name", "description", "planKey", "isActive", "scopeType", "slaStatus", "lastSuccessDate", "lastVerifiedDate" },
            new Object[] { "schedule", "Schedule and SLA", "schedule", Tier.T2, "cronExpression", "cronTimeZoneId", "scheduledJobId", "slaHours", "verifySlaDays", "verifyCronExpression", "verifyLevel" },
            new Object[] { "where", "Scope and storage", "storage", Tier.T2, "includeRoles", "storageBucketAlias", "pruneBucketAlias", "prefix", "critical", "consistency", "useReadReplica", "incrementalMode", "nativeDump" },
            new Object[] { "format", "Format and safety", "shield", Tier.T2, "format", "compression", "encryptionMode", "encryptionKeyRef", "sensitiveFieldPolicy", "heavyFieldPolicy", "fileObjectPolicy", "fileMissingPolicy" },
            new Object[] { "retention", "Retention and lock", "lock_clock", Tier.T2, "retainDaily", "retainWeekly", "retainMonthly", "retainYearly", "retainMinimum", "objectLockMode", "objectLockDays" },
            new Object[] { "system", "Limits and history", "history", Tier.T3, "maxDurationMinutes", "pageSize", "targetPartBytes", "ownerUserId", "lastRunId", "lastSuccessRunId", "lastVerifiedRunId", "id", "createDate", "modifyDate" });
         case RUN -> List.of(
            new Object[] { "run", "Run", "history", Tier.T1, "runKey", "backupPlanId", "status", "trigger", "startDate", "endDate", "durationMs", "errorMessage" },
            new Object[] { "volume", "Rows and size", "data_usage", Tier.T2, "tablesPlanned", "tablesDone", "rowsTotal", "bytesRaw", "bytesStored", "consistencyAchieved", "warningCount" },
            new Object[] { "files", "Files", "attach_file", Tier.T2, "filesReferenced", "filesCopied", "filesDeduplicated", "filesMissing", "fileBytesCopied", "fileBytesReferenced" },
            new Object[] { "storage", "Storage and integrity", "storage", Tier.T2, "storageBucketAlias", "runPrefix", "manifestKey", "manifestSha256", "snapshotId", "schemaFingerprint", "securityScope", "encryptionKeyRef", "encryptionKeyFingerprint", "verificationStatus" },
            new Object[] { "retention", "Retention", "lock_clock", Tier.T2, "retentionClass", "lockedUntil", "prunedDate", "prunedByUserId" },
            new Object[] { "system", "System", "settings", Tier.T3, "requestedByUserId", "heartbeatDate", "qqqVersion", "appVersion", "instanceName", "errorDetail", "id", "createDate", "modifyDate" });
         case VERIFICATION -> List.of(
            new Object[] { "result", "Result", "verified", Tier.T1, "backupRunId", "level", "status", "tablesChecked", "rowsChecked", "mismatches", "scratchTarget", "startDate", "endDate" },
            new Object[] { "system", "Detail", "settings", Tier.T3, "trigger", "requestedByUserId", "detailJson", "id", "createDate", "modifyDate" });
         case RESTORE_RUN -> List.of(
            new Object[] { "restore", "Restore", "restore", Tier.T1, "sourceBackupRunId", "status", "mode", "idStrategy", "matchOn", "targetInstanceName", "startDate", "endDate", "errorMessage" },
            new Object[] { "rows", "Rows", "table_rows", Tier.T2, "rowsInserted", "rowsUpdated", "rowsUnchanged", "rowsDeleted", "rowsSkipped", "rowsErrored", "sequencesResynced" },
            new Object[] { "files", "Files", "attach_file", Tier.T2, "filesRestored", "filesAlreadyPresent", "filesConflicting", "filesMissingFromBackup", "fileBytesRestored" },
            new Object[] { "system", "Safety and system", "settings", Tier.T3, "preRestoreBackupRunId", "scopeSummary", "automationsSuppressed", "esbSuppressed", "confirmationText", "requestedByUserId", "id", "createDate", "modifyDate" });
         default -> List.of();
      };
      if(layout.isEmpty())
      {
         return (table);
      }
      java.util.Set<String> placed = new java.util.HashSet<>();
      for(int i = 0; i < layout.size(); i++)
      {
         Object[]     spec   = layout.get(i);
         List<String> fields = new ArrayList<>();
         for(int f = 4; f < spec.length; f++)
         {
            fields.add((String) spec[f]);
         }
         if(i == layout.size() - 1)
         {
            table.getFields().keySet().stream().filter(name -> !placed.contains(name) && !fields.contains(name)).forEach(fields::add);
         }
         placed.addAll(fields);
         table.withSection(new QFieldSection((String) spec[0], (String) spec[1], new QIcon((String) spec[2]), (Tier) spec[3], fields));
      }
      for(ChildList childList : CHILD_LISTS)
      {
         if(childList.parent().equals(table.getName()))
         {
            table.withSection(new QFieldSection(childList.key(), new QIcon(childList.icon()), Tier.T2).withLabel(childList.label()).withWidgetName(childList.joinName()));
         }
      }
      return (table);
   }



   /** A child list on a parent record: its join, the columns shown, and the order. */
   record ChildList(String parent, String joinName, String key, String label, String icon, int maxRows, boolean canAdd, String orderField, List<String> columns)
   {
   }

   /** The relations shown on the record screens (sub-tables are child lists here, not in the nav). */
   static final List<ChildList> CHILD_LISTS = List.of(
      new ChildList(PLAN, "backupsPlanScopeJoinPlan", "scopeRules", "Scope rules", "rule", 50, true, "id", List.of("action", "selectorType", "selectorValue", "includeAssociatedChildren", "fieldExclusions")),
      new ChildList(PLAN, "backupsRunJoinPlan", "runs", "Runs", "history", 10, false, "id", List.of("runKey", "status", "startDate", "durationMs", "rowsTotal", "bytesStored")),
      new ChildList(RUN, "backupsArtifactJoinRun", "artifacts", "Artifacts", "inventory_2", 100, false, "id", List.of("tableName", "artifactType", "partNumber", "rowCount", "bytesStored", "status")),
      new ChildList(RUN, "backupsVerificationJoinRun", "verifications", "Verifications", "verified", 20, false, "id", List.of("level", "status", "tablesChecked", "rowsChecked", "mismatches", "startDate")),
      new ChildList(RUN, "backupsRestoreRunJoinRun", "restores", "Restores", "restore", 20, false, "id", List.of("status", "mode", "startDate", "rowsInserted", "rowsUpdated", "rowsErrored")),
      new ChildList(RESTORE_RUN, "backupsRestoreRunTableJoinRestoreRun", "restoreTables", "Tables restored", "table_rows", 100, false, "id",
         List.of("tableName", "targetTableName", "rowsInserted", "rowsUpdated", "rowsUnchanged", "rowsErrored", "schemaStatus")));



   /*******************************************************************************
    ** The child-list widgets for the record screens (see CHILD_LISTS).  Each shows a
    ** few readable columns, newest first, and never scrolls sideways.
    *******************************************************************************/
   public static List<QWidgetMetaData> widgets()
   {
      Map<String, QJoinMetaData> byName = new LinkedHashMap<>();
      joins().forEach(join -> byName.put(join.getName(), join));
      List<QWidgetMetaData> rs = new ArrayList<>();
      for(ChildList childList : CHILD_LISTS)
      {
         rs.add(ChildRecordListRenderer.widgetMetaDataBuilder(byName.get(childList.joinName())).withLabel(childList.label()).withMaxRows(childList.maxRows())
            .withCanAddChildRecord(childList.canAdd()).withOnlyIncludeFieldNames(childList.columns())
            .withOrderBys(List.of(new QFilterOrderBy(childList.orderField(), !PLAN.equals(childList.parent()) || childList.canAdd())))
            .getWidgetMetaData().withPermissionRules(permissionRules()));
      }
      return (rs);
   }



   /*******************************************************************************
    ** A table with the common id/createDate/modifyDate fields.
    *******************************************************************************/
   private static QTableMetaData base(BackupsQBitConfig config, String name, String label, String icon)
   {
      return (new QTableMetaData()
         .withName(name)
         .withLabel(label)
         .withIcon(new QIcon(icon))
         .withBackendName(config.getBackendName())
         .withPrimaryKeyField("id")
         .withPermissionRules(permissionRules())
         .withField(new QFieldMetaData("id", QFieldType.INTEGER).withIsEditable(false))
         .withField(new QFieldMetaData("createDate", QFieldType.DATE_TIME).withIsEditable(false))
         .withField(new QFieldMetaData("modifyDate", QFieldType.DATE_TIME).withIsEditable(false)));
   }



   /*******************************************************************************
    ** A read-only field.
    *******************************************************************************/
   private static QFieldMetaData ro(String name, QFieldType type)
   {
      return (new QFieldMetaData(name, type).withIsEditable(false));
   }



   /*******************************************************************************
    ** A read-only field backed by an enum possible value source.
    *******************************************************************************/
   private static QFieldMetaData roEnum(String name, String enumKey)
   {
      return (ro(name, QFieldType.STRING).withPossibleValueSourceName(pvs(enumKey)));
   }



   /*******************************************************************************
    ** An editable enum field with a default.
    *******************************************************************************/
   private static QFieldMetaData enumField(String name, String enumKey, String defaultValue)
   {
      QFieldMetaData field = new QFieldMetaData(name, QFieldType.STRING).withPossibleValueSourceName(pvs(enumKey));
      if(defaultValue != null)
      {
         field.withDefaultValue(defaultValue);
      }
      return (field);
   }



   /*******************************************************************************
    ** A read-only integer count.
    *******************************************************************************/
   private static QFieldMetaData roInt(String name)
   {
      return (ro(name, QFieldType.INTEGER));
   }



   /*******************************************************************************
    ** A read-only long (byte counts, millis).
    *******************************************************************************/
   private static QFieldMetaData roLong(String name)
   {
      return (ro(name, QFieldType.LONG));
   }



   /*******************************************************************************
    ** backups_plan: what, how, where, when and how long (spec 4.1).
    *******************************************************************************/
   static QTableMetaData plan(BackupsQBitConfig config)
   {
      QTableMetaData table = planBase(config);

      ////////////////////////////////////////////////////////////////////////////////////////
      // save validation (spec 4.1, 11.1), and the plan -> scheduled job sync when the qbit  //
      // has a scheduler (spec 9.1): one job per active plan, and one for the verify cron    //
      ////////////////////////////////////////////////////////////////////////////////////////
      QCodeReference validation = new QCodeReference(BackupPlanTableCustomizer.class);
      table.withCustomizer(TableCustomizers.PRE_INSERT_RECORD, validation);
      table.withCustomizer(TableCustomizers.PRE_UPDATE_RECORD, validation);
      if(StringUtils.hasContent(config.getSchedulerName()))
      {
         QCodeReferenceWithProperties sync = MultiCustomizer.of(
            BaseSyncToScheduledJobTableCustomizer.makeCodeReference(table, new BackupPlanSyncToScheduledJobProcess()),
            BaseSyncToScheduledJobTableCustomizer.makeCodeReference(table, new BackupPlanVerifySyncToScheduledJobProcess()));
         table.withCustomizer(TableCustomizers.POST_INSERT_RECORD, sync);
         table.withCustomizer(TableCustomizers.POST_UPDATE_RECORD, sync);
         table.withCustomizer(TableCustomizers.POST_DELETE_RECORD, sync);
      }
      return (table);
   }



   /*******************************************************************************
    ** The plan table before the customizers.
    *******************************************************************************/
   private static QTableMetaData planBase(BackupsQBitConfig config)
   {
      return (base(config, PLAN, "Backup Plan", "backup")
         .withRecordLabelFormatAndFields("%s", "name")
         .withUniqueKey(new UniqueKey("name"))
         .withUniqueKey(new UniqueKey("planKey"))
         .withAssociation(new Association().withName("scopes").withAssociatedTableName(PLAN_SCOPE).withJoinName("backupsPlanScopeJoinPlan"))
         .withField(new QFieldMetaData("name", QFieldType.STRING).withIsRequired(true).withMaxLength(100))
         .withField(new QFieldMetaData("description", QFieldType.TEXT))
         .withField(new QFieldMetaData("planKey", QFieldType.STRING).withIsRequired(true).withMaxLength(60))
         .withField(new QFieldMetaData("isActive", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(enumField("scopeType", "scope_type", "INSTANCE").withIsRequired(true))
         .withField(new QFieldMetaData("includeRoles", QFieldType.STRING).withDefaultValue("DATA,AUDIT").withMaxLength(100))
         .withField(new QFieldMetaData("storageBucketAlias", QFieldType.STRING).withIsRequired(true).withMaxLength(100))
         .withField(new QFieldMetaData("pruneBucketAlias", QFieldType.STRING).withMaxLength(100))
         .withField(new QFieldMetaData("prefix", QFieldType.STRING).withMaxLength(255))
         .withField(new QFieldMetaData("critical", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(enumField("format", "format", "JSONL"))
         .withField(enumField("compression", "compression", "GZIP"))
         .withField(enumField("encryptionMode", "encryption_mode", "NONE"))
         .withField(new QFieldMetaData("encryptionKeyRef", QFieldType.STRING).withMaxLength(100))
         .withField(enumField("sensitiveFieldPolicy", "sensitive_policy", "EXCLUDE"))
         .withField(enumField("heavyFieldPolicy", "heavy_policy", "INLINE"))
         .withField(enumField("fileObjectPolicy", "file_policy", "COPY"))
         .withField(enumField("fileMissingPolicy", "file_missing_policy", "WARN"))
         .withField(enumField("consistency", "consistency", "SNAPSHOT_PREFERRED"))
         .withField(new QFieldMetaData("useReadReplica", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(enumField("incrementalMode", "incremental_mode", "FULL"))
         .withField(enumField("nativeDump", "native_dump", "NONE"))
         .withField(new QFieldMetaData("cronExpression", QFieldType.STRING).withMaxLength(100))
         .withField(new QFieldMetaData("cronTimeZoneId", QFieldType.STRING).withMaxLength(60))
         .withField(roInt("scheduledJobId"))
         .withField(new QFieldMetaData("slaHours", QFieldType.INTEGER).withDefaultValue(26))
         .withField(new QFieldMetaData("verifySlaDays", QFieldType.INTEGER).withDefaultValue(8))
         .withField(new QFieldMetaData("verifyFileSamplePercent", QFieldType.INTEGER).withDefaultValue(100))
         .withField(new QFieldMetaData("verifyCronExpression", QFieldType.STRING).withMaxLength(100))
         .withField(enumField("verifyLevel", "verify_level", "CONTENT"))
         .withField(new QFieldMetaData("retainDaily", QFieldType.INTEGER).withDefaultValue(7))
         .withField(new QFieldMetaData("retainWeekly", QFieldType.INTEGER).withDefaultValue(4))
         .withField(new QFieldMetaData("retainMonthly", QFieldType.INTEGER).withDefaultValue(12))
         .withField(new QFieldMetaData("retainYearly", QFieldType.INTEGER).withDefaultValue(0))
         .withField(new QFieldMetaData("retainMinimum", QFieldType.INTEGER).withDefaultValue(3))
         .withField(enumField("objectLockMode", "lock_mode", "NONE"))
         .withField(new QFieldMetaData("objectLockDays", QFieldType.INTEGER))
         .withField(new QFieldMetaData("maxDurationMinutes", QFieldType.INTEGER).withDefaultValue(240))
         .withField(new QFieldMetaData("pageSize", QFieldType.INTEGER).withDefaultValue(5000))
         .withField(new QFieldMetaData("targetPartBytes", QFieldType.LONG).withDefaultValue(268435456L))
         .withField(new QFieldMetaData("ownerUserId", QFieldType.STRING).withMaxLength(100))
         .withField(ro("lastRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(ro("lastSuccessRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(ro("lastSuccessDate", QFieldType.DATE_TIME))
         .withField(ro("lastVerifiedRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(ro("lastVerifiedDate", QFieldType.DATE_TIME))
         .withField(roEnum("slaStatus", "sla_status")));
   }



   /*******************************************************************************
    ** backups_plan_scope: one row per inclusion or exclusion (spec 4.2).
    *******************************************************************************/
   static QTableMetaData planScope(BackupsQBitConfig config)
   {
      return (base(config, PLAN_SCOPE, "Backup Plan Scope", "filter_list")
         .withRecordLabelFormatAndFields("%s %s %s", "action", "selectorType", "selectorValue")
         .withField(new QFieldMetaData("backupPlanId", QFieldType.INTEGER).withIsRequired(true).withPossibleValueSourceName(PLAN))
         .withField(enumField("action", "scope_action", "INCLUDE").withIsRequired(true))
         .withField(enumField("selectorType", "selector_type", "TABLE").withIsRequired(true))
         .withField(new QFieldMetaData("selectorValue", QFieldType.STRING).withIsRequired(true).withMaxLength(200))
         .withField(new QFieldMetaData("filterJson", QFieldType.TEXT))
         .withField(new QFieldMetaData("includeAssociatedChildren", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(new QFieldMetaData("fieldExclusions", QFieldType.STRING).withMaxLength(1000)));
   }



   /*******************************************************************************
    ** backups_run: one execution of a plan, or an ad-hoc export (spec 4.3).
    *******************************************************************************/
   static QTableMetaData run(BackupsQBitConfig config)
   {
      return (base(config, RUN, "Backup Run", "history")
         .withRecordLabelFormatAndFields("%s", "runKey")
         .withUniqueKey(new UniqueKey("runKey"))
         .withField(ro("backupPlanId", QFieldType.INTEGER).withPossibleValueSourceName(PLAN))
         .withField(ro("runKey", QFieldType.STRING))
         .withField(roEnum("trigger", "run_trigger"))
         .withField(ro("requestedByUserId", QFieldType.STRING))
         .withField(roEnum("status", "run_status"))
         .withField(ro("startDate", QFieldType.DATE_TIME))
         .withField(ro("endDate", QFieldType.DATE_TIME))
         .withField(roLong("durationMs"))
         .withField(ro("heartbeatDate", QFieldType.DATE_TIME))
         .withField(roInt("tablesPlanned"))
         .withField(roInt("tablesDone"))
         .withField(roLong("rowsTotal"))
         .withField(roLong("bytesRaw"))
         .withField(roLong("bytesStored"))
         .withField(roInt("filesReferenced"))
         .withField(roInt("filesCopied"))
         .withField(roInt("filesDeduplicated"))
         .withField(roInt("filesMissing"))
         .withField(roLong("fileBytesCopied"))
         .withField(roLong("fileBytesReferenced"))
         .withField(roEnum("consistencyAchieved", "consistency_achieved"))
         .withField(ro("snapshotId", QFieldType.STRING))
         .withField(ro("storageBucketAlias", QFieldType.STRING))
         .withField(ro("runPrefix", QFieldType.STRING))
         .withField(ro("manifestKey", QFieldType.STRING))
         .withField(ro("manifestSha256", QFieldType.STRING))
         .withField(ro("qqqVersion", QFieldType.STRING))
         .withField(ro("appVersion", QFieldType.STRING))
         .withField(ro("instanceName", QFieldType.STRING))
         .withField(ro("schemaFingerprint", QFieldType.STRING))
         .withField(ro("securityScope", QFieldType.STRING))
         .withField(ro("encryptionKeyRef", QFieldType.STRING))
         .withField(ro("encryptionKeyFingerprint", QFieldType.STRING))
         .withField(roEnum("retentionClass", "retention_class"))
         .withField(ro("lockedUntil", QFieldType.DATE_TIME))
         .withField(ro("prunedDate", QFieldType.DATE_TIME))
         .withField(ro("prunedByUserId", QFieldType.STRING))
         .withField(roEnum("verificationStatus", "verification_status"))
         .withField(roInt("warningCount"))
         .withField(ro("errorMessage", QFieldType.TEXT))
         .withField(ro("errorDetail", QFieldType.TEXT)));
   }



   /*******************************************************************************
    ** backups_artifact: one stored object or part (spec 4.4).
    *******************************************************************************/
   static QTableMetaData artifact(BackupsQBitConfig config)
   {
      return (base(config, ARTIFACT, "Backup Artifact", "inventory_2")
         .withRecordLabelFormatAndFields("%s", "objectKey")
         .withField(ro("backupRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(roEnum("artifactType", "artifact_type"))
         .withField(ro("tableName", QFieldType.STRING))
         .withField(roInt("partNumber"))
         .withField(ro("objectKey", QFieldType.STRING))
         .withField(ro("format", QFieldType.STRING))
         .withField(ro("compression", QFieldType.STRING))
         .withField(ro("encrypted", QFieldType.BOOLEAN))
         .withField(roLong("rowCount"))
         .withField(roLong("bytesRaw"))
         .withField(roLong("bytesStored"))
         .withField(ro("sha256Stored", QFieldType.STRING))
         .withField(ro("sha256Raw", QFieldType.STRING))
         .withField(ro("rowsHash", QFieldType.STRING))
         .withField(ro("firstKey", QFieldType.STRING))
         .withField(ro("lastKey", QFieldType.STRING))
         .withField(ro("tableFingerprint", QFieldType.STRING))
         .withField(roEnum("status", "artifact_status"))
         .withField(ro("storageVersionId", QFieldType.STRING)));
   }



   /*******************************************************************************
    ** backups_verification: the result of a verify (spec 4.5).
    *******************************************************************************/
   static QTableMetaData verification(BackupsQBitConfig config)
   {
      return (base(config, VERIFICATION, "Backup Verification", "verified")
         .withRecordLabelFormatAndFields("%s %s", "level", "status")
         .withField(ro("backupRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(roEnum("level", "verify_level"))
         .withField(roEnum("trigger", "run_trigger"))
         .withField(ro("requestedByUserId", QFieldType.STRING))
         .withField(ro("startDate", QFieldType.DATE_TIME))
         .withField(ro("endDate", QFieldType.DATE_TIME))
         .withField(roEnum("status", "verify_result"))
         .withField(roInt("tablesChecked"))
         .withField(roLong("rowsChecked"))
         .withField(roInt("mismatches"))
         .withField(ro("scratchTarget", QFieldType.STRING))
         .withField(ro("detailJson", QFieldType.TEXT)));
   }



   /*******************************************************************************
    ** backups_restore_run: one restore (spec 4.6).
    *******************************************************************************/
   static QTableMetaData restoreRun(BackupsQBitConfig config)
   {
      return (base(config, RESTORE_RUN, "Backup Restore", "restore")
         .withRecordLabelFormatAndFields("Restore %s", "id")
         .withField(ro("sourceBackupRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(ro("requestedByUserId", QFieldType.STRING))
         .withField(ro("startDate", QFieldType.DATE_TIME))
         .withField(ro("endDate", QFieldType.DATE_TIME))
         .withField(roEnum("status", "restore_status"))
         .withField(roEnum("mode", "restore_mode"))
         .withField(roEnum("idStrategy", "id_strategy"))
         .withField(ro("matchOn", QFieldType.STRING))
         .withField(ro("targetInstanceName", QFieldType.STRING))
         .withField(ro("scopeSummary", QFieldType.TEXT))
         .withField(ro("preRestoreBackupRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(roLong("rowsInserted"))
         .withField(roLong("rowsUpdated"))
         .withField(roLong("rowsUnchanged"))
         .withField(roLong("rowsDeleted"))
         .withField(roLong("rowsSkipped"))
         .withField(roLong("rowsErrored"))
         .withField(roInt("filesRestored"))
         .withField(roInt("filesAlreadyPresent"))
         .withField(roInt("filesConflicting"))
         .withField(roInt("filesMissingFromBackup"))
         .withField(roLong("fileBytesRestored"))
         .withField(roInt("sequencesResynced"))
         .withField(ro("automationsSuppressed", QFieldType.BOOLEAN))
         .withField(ro("esbSuppressed", QFieldType.BOOLEAN))
         .withField(ro("confirmationText", QFieldType.STRING))
         .withField(ro("errorMessage", QFieldType.TEXT)));
   }



   /*******************************************************************************
    ** backups_restore_run_table: per-table restore results (spec 4.7).
    *******************************************************************************/
   static QTableMetaData restoreRunTable(BackupsQBitConfig config)
   {
      return (base(config, RESTORE_RUN_TABLE, "Backup Restore Table", "table_rows")
         .withRecordLabelFormatAndFields("%s", "tableName")
         .withField(ro("backupRestoreRunId", QFieldType.INTEGER).withPossibleValueSourceName(RESTORE_RUN))
         .withField(ro("tableName", QFieldType.STRING))
         .withField(ro("targetTableName", QFieldType.STRING))
         .withField(roLong("rowsInserted"))
         .withField(roLong("rowsUpdated"))
         .withField(roLong("rowsUnchanged"))
         .withField(roLong("rowsDeleted"))
         .withField(roLong("rowsSkipped"))
         .withField(roLong("rowsErrored"))
         .withField(roEnum("schemaStatus", "schema_status"))
         .withField(ro("unmappedFields", QFieldType.STRING))
         .withField(ro("sequenceResynced", QFieldType.BOOLEAN))
         .withField(ro("status", QFieldType.STRING)));
   }



   /*******************************************************************************
    ** backups_file_object: the content-addressed file store (spec 4.8).
    *******************************************************************************/
   static QTableMetaData fileObject(BackupsQBitConfig config)
   {
      return (base(config, FILE_OBJECT, "Backup File", "attach_file")
         .withRecordLabelFormatAndFields("%s", "sha256")
         .withUniqueKey(new UniqueKey("sha256"))
         .withField(ro("sha256", QFieldType.STRING))
         .withField(roLong("sizeBytes"))
         .withField(ro("contentType", QFieldType.STRING))
         .withField(ro("storageBucketAlias", QFieldType.STRING))
         .withField(ro("objectKey", QFieldType.STRING))
         .withField(ro("encrypted", QFieldType.BOOLEAN))
         .withField(ro("encryptionKeyFingerprint", QFieldType.STRING))
         .withField(ro("firstRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(ro("lastReferencedRunId", QFieldType.INTEGER).withPossibleValueSourceName(RUN))
         .withField(roInt("referenceCount"))
         .withField(ro("lastVerifiedDate", QFieldType.DATE_TIME))
         .withField(ro("lastVerifyStatus", QFieldType.STRING))
         .withField(ro("lockedUntil", QFieldType.DATE_TIME))
         .withField(ro("deletedDate", QFieldType.DATE_TIME)));
   }



   /*******************************************************************************
    ** backups_id_map: transient old-to-new ids for REMAP_NEW_IDS restores (spec 4.9).
    *******************************************************************************/
   static QTableMetaData idMap(BackupsQBitConfig config)
   {
      return (base(config, ID_MAP, "Backup ID Map", "swap_horiz")
         .withRecordLabelFormatAndFields("%s %s", "tableName", "oldId")
         .withField(ro("restoreRunId", QFieldType.INTEGER).withPossibleValueSourceName(RESTORE_RUN))
         .withField(ro("tableName", QFieldType.STRING))
         .withField(ro("oldId", QFieldType.STRING))
         .withField(ro("newId", QFieldType.STRING)));
   }
}
