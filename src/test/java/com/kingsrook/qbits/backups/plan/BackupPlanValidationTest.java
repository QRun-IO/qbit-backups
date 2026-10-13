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

package com.kingsrook.qbits.backups.plan;


import java.io.Serializable;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunCoordinator;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.InMemoryBackupStorage;
import com.kingsrook.qqq.backend.core.actions.permissions.PermissionsHelper;
import com.kingsrook.qqq.backend.core.actions.permissions.TablePermissionSubType;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** What a plan may be saved as (spec 4.1, 6.2, 11.1, 11.3): the refusals are clear
 ** messages at save time, not failures on the first night.
 *******************************************************************************/
class BackupPlanValidationTest
{
   private final BackupCatalog catalog = new BackupCatalog();



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(3);
      BackupValidatorReset.reset();
   }



   /*******************************************************************************
    ** Save a plan through the table, returning the record (with its errors) so the
    ** test can read what the save said.
    *******************************************************************************/
   private QRecord save(Map<String, Serializable> overrides) throws QException
   {
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("name", "P" + System.nanoTime(), "planKey", "p" + System.nanoTime(), "isActive", true, "scopeType", "INSTANCE", "storageBucketAlias", "backups"));
      values.putAll(overrides);
      QRecord record = new QRecord();
      record.setValues(values);
      InsertInput input = new InsertInput(BackupsTables.PLAN);
      input.setRecords(List.of(record));
      input.withPermissionBypass("test");
      return (new InsertAction().execute(input).getRecords().get(0));
   }



   /*******************************************************************************
    ** Update one plan through the table, returning the record with its errors.
    *******************************************************************************/
   private QRecord update(Integer id, String field, Serializable value) throws QException
   {
      UpdateInput input = new UpdateInput(BackupsTables.PLAN);
      input.setRecords(List.of(new QRecord().withValue("id", id).withValue(field, value)));
      input.withPermissionBypass("test");
      return (new UpdateAction().execute(input).getRecords().get(0));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static List<String> errors(QRecord record)
   {
      return (record.getErrors().stream().map(e -> e.getMessage()).toList());
   }



   /*******************************************************************************
    ** A plain plan saves, with a warning that no prune bucket is set (one key can
    ** write and delete).
    *******************************************************************************/
   @Test
   void testAGoodPlanSaves() throws Exception
   {
      QRecord saved = save(Map.of());
      assertThat(errors(saved)).isEmpty();
      assertThat(saved.getValueInteger("id")).isNotNull();
      assertThat(saved.getWarnings()).anyMatch(w -> w.getMessage().contains("No prune bucket"));
   }



   /*******************************************************************************
    ** An object lock the bucket cannot do is refused, with what the bucket can do; a
    ** lock needs a number of days; and a lock on a capable bucket is fine.
    *******************************************************************************/
   @Test
   void testObjectLockIsCheckedAgainstTheBucketCapabilities() throws Exception
   {
      InMemoryBackupStorage noLock = new InMemoryBackupStorage().withCapabilities(EnumSet.of(BackupStorage.StorageCapability.MULTIPART));
      BackupsRuntime.getConfig().withStorageResolver(alias -> "garage".equals(alias) ? noLock : "backups".equals(alias) ? BackupsTestUtils.BACKUPS : BackupsTestUtils.MEDIA);

      assertThat(errors(save(Map.of("storageBucketAlias", "garage", "objectLockMode", "GOVERNANCE", "objectLockDays", 14))))
         .singleElement().asString().contains("does not support object lock").contains("garage").contains("MULTIPART");
      assertThat(errors(save(Map.of("objectLockMode", "COMPLIANCE")))).singleElement().asString().contains("number of days");
      assertThat(errors(save(Map.of("objectLockMode", "GOVERNANCE", "objectLockDays", 14)))).isEmpty();
      assertThat(errors(save(Map.of("storageBucketAlias", "garage")))).as("no lock asked, none needed").isEmpty();
      assertThat(errors(save(Map.of("objectLockMode", "SOMETIMES")))).anyMatch(m -> m.contains("Unknown object lock mode"));
   }



   /*******************************************************************************
    ** An unknown bucket or prune bucket is refused.
    *******************************************************************************/
   @Test
   void testUnknownBucketsAreRefused() throws Exception
   {
      assertThat(errors(save(Map.of("storageBucketAlias", "nowhere")))).singleElement().asString().contains("nowhere").contains("cannot be used");
      assertThat(errors(save(Map.of("pruneBucketAlias", "nowhere")))).singleElement().asString().contains("prune bucket nowhere");
   }



   /*******************************************************************************
    ** Including sensitive fields only encrypted needs encryption; client-side
    ** encryption is refused until a cipher is installed, and then needs a key name.
    *******************************************************************************/
   @Test
   void testEncryptionRules() throws Exception
   {
      assertThat(errors(save(Map.of("sensitiveFieldPolicy", "INCLUDE_ENCRYPTED_ONLY")))).singleElement().asString().contains("requires client-side encryption");
      assertThat(errors(save(Map.of("encryptionMode", "CLIENT_AES256_GCM", "encryptionKeyRef", "kek")))).singleElement().asString().contains("not available in this version");
      assertThat(errors(save(Map.of("sensitiveFieldPolicy", "INCLUDE_ENCRYPTED_ONLY", "encryptionMode", "CLIENT_AES256_GCM", "encryptionKeyRef", "kek")))).hasSize(1);

      BackupPlanValidator.setEncryptionAvailable(true);
      assertThat(errors(save(Map.of("sensitiveFieldPolicy", "INCLUDE_ENCRYPTED_ONLY", "encryptionMode", "CLIENT_AES256_GCM", "encryptionKeyRef", "kek")))).isEmpty();
      assertThat(errors(save(Map.of("encryptionMode", "CLIENT_AES256_GCM")))).singleElement().asString().contains("encryption key reference");
   }



   /*******************************************************************************
    ** Formats, compressions and options that are not built are refused with a clear
    ** message that names what to use instead.
    *******************************************************************************/
   @Test
   void testUnsupportedFormatsAreRefusedClearly() throws Exception
   {
      assertThat(errors(save(Map.of("format", "PARQUET")))).singleElement().asString().isEqualTo("The PARQUET format is not available yet. Use JSONL, CSV or JSONL_AND_CSV.");
      assertThat(errors(save(Map.of("compression", "ZSTD")))).singleElement().asString().isEqualTo("ZSTD compression is not available yet. Use GZIP or NONE.");
      assertThat(errors(save(Map.of("incrementalMode", "CHANGED_SINCE_LAST")))).singleElement().asString().contains("Incremental backups are not available yet");
      assertThat(errors(save(Map.of("nativeDump", "PG_DUMP_CUSTOM")))).singleElement().asString().contains("Native database dumps are not available yet");
      assertThat(errors(save(Map.of("format", "JSONL_AND_CSV", "compression", "NONE")))).isEmpty();
   }



   /*******************************************************************************
    ** Cron expressions, retention numbers and plan keys.
    *******************************************************************************/
   @Test
   void testScheduleRetentionAndKeyRules() throws Exception
   {
      assertThat(errors(save(Map.of("cronExpression", "not a cron")))).singleElement().asString().contains("cronExpression is not a valid cron expression");
      assertThat(errors(save(Map.of("verifyCronExpression", "0 0 4 ? * SUN")))).isEmpty();
      assertThat(errors(save(Map.of("retainDaily", -1)))).singleElement().asString().isEqualTo("retainDaily cannot be negative.");
      assertThat(errors(save(Map.of("planKey", "Has Spaces")))).singleElement().asString().contains("plan key must be lowercase");
   }



   /*******************************************************************************
    ** The plan key is part of every object key, so it is fixed once the plan has run.
    *******************************************************************************/
   @Test
   void testPlanKeyIsImmutableAfterTheFirstRun() throws Exception
   {
      QRecord plan = save(Map.of("planKey", "nightly"));
      QRecord renamed = update(plan.getValueInteger("id"), "planKey", "renamed");
      assertThat(errors(renamed)).as("before the first run it can change").isEmpty();

      assertThat(new BackupRunCoordinator().runPlan(catalog.get(BackupsTables.PLAN, plan.getValueInteger("id")).orElseThrow(), "MANUAL").isUsable()).isTrue();

      QRecord again = update(plan.getValueInteger("id"), "planKey", "nightly-2");
      assertThat(errors(again)).singleElement().asString().contains("cannot change after the plan has run");

      QRecord fine = update(plan.getValueInteger("id"), "slaHours", 30);
      assertThat(errors(fine)).isEmpty();
      assertThat(catalog.get(BackupsTables.PLAN, plan.getValueInteger("id")).orElseThrow().getValueString("planKey")).isEqualTo("renamed");
   }



   /*******************************************************************************
    ** Saving an active plan needs backups.admin; an inactive plan is only a draft.
    *******************************************************************************/
   @Test
   void testActivePlanNeedsTheAdminPermission() throws Exception
   {
      BackupsRuntime.getConfig().withOpenWhenAnonymous(false);
      QContext.setQSession(new QSession().withPermission("backups.view"));
      assertThat(errors(save(Map.of()))).singleElement().asString().contains("needs the backups.admin permission");
      assertThat(errors(save(Map.of("isActive", false)))).as("a draft").isEmpty();

      QContext.setQSession(new QSession().withPermission("backups.admin"));
      assertThat(errors(save(Map.of()))).isEmpty();
   }



   /*******************************************************************************
    ** The saving user must be able to read every table the plan covers; the message
    ** lists the ones they cannot.
    *******************************************************************************/
   @Test
   void testOwnerMustReadEveryTable() throws Exception
   {
      BackupsRuntime.getConfig().withOpenWhenAnonymous(false);
      QContext.getQInstance().getTable("author").withPermissionRules(new QPermissionRules().withLevel(PermissionLevel.READ_WRITE_PERMISSIONS));
      String authorRead = PermissionsHelper.getTablePermissionName("author", TablePermissionSubType.READ);

      QContext.setQSession(new QSession().withPermission("backups.admin"));
      assertThat(errors(save(Map.of()))).singleElement().asString().contains("not allowed to read").contains("author").doesNotContain("blogPost");

      QContext.setQSession(new QSession().withPermissions("backups.admin", authorRead));
      assertThat(errors(save(Map.of()))).isEmpty();
   }



   /*******************************************************************************
    ** The saving user becomes the owner when none is given, and is never overwritten.
    *******************************************************************************/
   @Test
   void testSavingUserBecomesOwner() throws Exception
   {
      QContext.setQSession(new QSession().withUser(new com.kingsrook.qqq.backend.core.model.session.QUser().withIdReference("jmaes")));
      QRecord mine = save(Map.of());
      assertThat(mine.getValueString("ownerUserId")).isEqualTo("jmaes");
      QRecord given = save(Map.of("ownerUserId", "someone"));
      assertThat(given.getValueString("ownerUserId")).isEqualTo("someone");
   }
}
