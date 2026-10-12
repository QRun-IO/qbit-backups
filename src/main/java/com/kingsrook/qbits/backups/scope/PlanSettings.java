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

package com.kingsrook.qbits.backups.scope;


import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** The settings of a backup plan (a backups_plan record), with the defaults of
 ** spec 4.1 applied for anything not set.  Ad-hoc exports (no plan row) build one
 ** with the static factory and the with() method.
 *******************************************************************************/
public class PlanSettings
{
   private final Map<String, Serializable> values = new LinkedHashMap<>();

   private Integer planId;



   /*******************************************************************************
    ** From a plan record.
    *******************************************************************************/
   public static PlanSettings of(QRecord plan)
   {
      PlanSettings settings = new PlanSettings();
      if(plan != null)
      {
         settings.planId = plan.getValueInteger("id");
         settings.values.putAll(plan.getValues());
      }
      return (settings);
   }



   /*******************************************************************************
    ** An ad-hoc (no plan row) settings object with all defaults.
    *******************************************************************************/
   public static PlanSettings adHoc()
   {
      return (new PlanSettings());
   }



   /*******************************************************************************
    ** Set one value (fluent).
    *******************************************************************************/
   public PlanSettings with(String name, Serializable value)
   {
      values.put(name, value);
      return (this);
   }



   /*******************************************************************************
    ** The plan id, or null for an ad-hoc run.
    *******************************************************************************/
   public Integer getPlanId()
   {
      return (planId);
   }



   /*******************************************************************************
    ** A string setting.
    *******************************************************************************/
   public String string(String name, String defaultValue)
   {
      String value = ValueUtils.getValueAsString(values.get(name));
      return (value == null || value.isEmpty() ? defaultValue : value);
   }



   /*******************************************************************************
    ** An integer setting.
    *******************************************************************************/
   public Integer integer(String name, Integer defaultValue)
   {
      Integer value = ValueUtils.getValueAsInteger(values.get(name));
      return (value == null ? defaultValue : value);
   }



   /*******************************************************************************
    ** A long setting.
    *******************************************************************************/
   public Long longValue(String name, Long defaultValue)
   {
      Long value = ValueUtils.getValueAsLong(values.get(name));
      return (value == null ? defaultValue : value);
   }



   /*******************************************************************************
    ** A boolean setting.
    *******************************************************************************/
   public boolean bool(String name, boolean defaultValue)
   {
      Boolean value = ValueUtils.getValueAsBoolean(values.get(name));
      return (value == null ? defaultValue : value);
   }



   /*******************************************************************************
    ** The roles a plan includes by default (comma separated).
    *******************************************************************************/
   public List<TableRole> includeRoles()
   {
      String csv = string("includeRoles", "DATA,AUDIT");
      return (java.util.Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(TableRole::valueOf).toList());
   }



   /*******************************************************************************
    ** The plan key (slug used in object keys).
    *******************************************************************************/
   public String planKey()
   {
      return (string("planKey", "adhoc"));
   }



   /*******************************************************************************
    ** The scope type.
    *******************************************************************************/
   public String scopeType()
   {
      return (string("scopeType", "INSTANCE"));
   }



   /*******************************************************************************
    ** The format.
    *******************************************************************************/
   public String format()
   {
      return (string("format", "JSONL"));
   }



   /*******************************************************************************
    ** The compression.
    *******************************************************************************/
   public String compression()
   {
      return (string("compression", "GZIP"));
   }



   /*******************************************************************************
    ** The file object policy: COPY (default) or REFERENCE_ONLY.
    *******************************************************************************/
   public String fileObjectPolicy()
   {
      return (string("fileObjectPolicy", "COPY"));
   }



   /*******************************************************************************
    ** The page size.
    *******************************************************************************/
   public int pageSize()
   {
      return (integer("pageSize", 5000));
   }



   /*******************************************************************************
    ** Target part size in bytes.
    *******************************************************************************/
   public long targetPartBytes()
   {
      return (longValue("targetPartBytes", 268435456L));
   }



   /*******************************************************************************
    ** The settings as a map (for the manifest; contains no secrets by design).
    *******************************************************************************/
   public Map<String, Serializable> manifestSettings()
   {
      Map<String, Serializable> map = new LinkedHashMap<>();
      for(String key : List.of("scopeType", "includeRoles", "format", "compression", "encryptionMode", "sensitiveFieldPolicy", "heavyFieldPolicy",
         "fileObjectPolicy", "fileMissingPolicy", "consistency", "incrementalMode", "pageSize", "targetPartBytes"))
      {
         if(values.get(key) != null)
         {
            map.put(key, values.get(key));
         }
      }
      return (map);
   }
}
