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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qbits.backups.scope.PlanSettings;
import com.kingsrook.qbits.backups.scope.ScopeResolver;
import com.kingsrook.qqq.backend.core.actions.customizers.TableCustomizerInterface;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.AbstractActionInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.model.statusmessages.BadInputStatusMessage;
import com.kingsrook.qqq.backend.core.model.statusmessages.QWarningMessage;


/*******************************************************************************
 ** Plan save validation (pre insert and pre update of backups_plan).  See
 ** BackupPlanValidator for the rules.  An update that touches none of the plan
 ** configuration (the processes write the last run and SLA fields) is not
 ** validated again.
 **
 ** The saving user becomes the plan owner when none is given.
 *******************************************************************************/
public class BackupPlanTableCustomizer implements TableCustomizerInterface
{
   private static final List<String> CONFIGURATION_FIELDS = List.of("planKey", "isActive", "scopeType", "includeRoles", "storageBucketAlias", "pruneBucketAlias",
      "format", "compression", "encryptionMode", "encryptionKeyRef", "sensitiveFieldPolicy", "incrementalMode", "nativeDump", "cronExpression", "cronTimeZoneId",
      "verifyCronExpression", "retainDaily", "retainWeekly", "retainMonthly", "retainYearly", "retainMinimum", "objectLockMode", "objectLockDays",
      "slaHours", "pageSize", "ownerUserId");



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<QRecord> preInsertOrUpdate(AbstractActionInput input, List<QRecord> records, boolean isPreview, Optional<List<QRecord>> oldRecordList) throws QException
   {
      Map<Serializable, QRecord> oldById = oldRecordList.isPresent() ? oldRecordListToMap("id", oldRecordList).orElse(Map.of()) : Map.of();
      BackupCatalog              catalog = new BackupCatalog();

      for(QRecord record : records)
      {
         QRecord old = oldRecordList.isPresent() ? oldById.get(record.getValue("id")) : null;
         if(old != null && !touchesConfiguration(record))
         {
            continue;
         }

         QRecord merged = new QRecord();
         if(old != null)
         {
            merged.getValues().putAll(old.getValues());
         }
         merged.getValues().putAll(record.getValues());

         if(merged.getValue("ownerUserId") == null && Boolean.TRUE.equals(merged.getValueBoolean("isActive")))
         {
            QSession session = QContext.getQSession();
            String   owner   = session == null || session.getUser() == null ? null : session.getUser().getIdReference();
            if(owner != null)
            {
               record.setValue("ownerUserId", owner);
               merged.setValue("ownerUserId", owner);
            }
         }

         boolean hasRun = old != null && !catalog.where(BackupsTables.RUN, "backupPlanId", old.getValueInteger("id")).isEmpty();
         BackupPlanValidator.Result result = BackupPlanValidator.validate(PlanSettings.of(merged), old == null ? null : old.getValueString("planKey"), hasRun, scopeRules(record, old));
         for(String error : result.errors())
         {
            record.addError(new BadInputStatusMessage(error));
         }
         for(String warning : result.warnings())
         {
            record.addWarning(new QWarningMessage(warning));
         }
      }
      return (records);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static boolean touchesConfiguration(QRecord record)
   {
      for(String field : CONFIGURATION_FIELDS)
      {
         if(record.getValues().containsKey(field))
         {
            return (true);
         }
      }
      return (false);
   }



   /*******************************************************************************
    ** The scope rules to resolve: the ones sent with the plan (associated rows), or
    ** the saved ones.
    *******************************************************************************/
   private static List<ScopeResolver.ScopeRule> scopeRules(QRecord record, QRecord old) throws QException
   {
      List<QRecord> sent = record.getAssociatedRecords() == null ? null : record.getAssociatedRecords().get("scopes");
      if(sent != null)
      {
         List<ScopeResolver.ScopeRule> rules = new ArrayList<>();
         for(QRecord row : sent)
         {
            rules.add(new ScopeResolver.ScopeRule(!"EXCLUDE".equals(row.getValueString("action")), row.getValueString("selectorType"), row.getValueString("selectorValue"),
               row.getValueString("filterJson"), !Boolean.FALSE.equals(row.getValueBoolean("includeAssociatedChildren")), row.getValueString("fieldExclusions")));
         }
         return (rules);
      }
      return (old == null ? List.of() : BackupRunFactory.scopeRules(old.getValueInteger("id")));
   }
}
