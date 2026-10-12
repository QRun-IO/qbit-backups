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

package com.kingsrook.qbits.backups.manifest;


import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Association;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;


/*******************************************************************************
 ** A stable schema descriptor and SHA-256 fingerprint of a table (spec 5.5).
 **
 ** This is an internal stand-in for the QQQ core TableSchemaFingerprint (core PR
 ** feature/schema-fingerprint).  When that ships in the next 4.1.x RC, this class
 ** delegates to it and the descriptor below is kept only for reading old manifests.
 ** Labels, adornments and help text are excluded, so cosmetic changes do not count.
 *******************************************************************************/
public final class TableFingerprints
{

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private TableFingerprints()
   {
   }



   /*******************************************************************************
    ** The descriptor of a table (sorted fields; plain maps and lists, so it is also
    ** what goes in the manifest schema).
    *******************************************************************************/
   public static Map<String, Object> descriptor(QTableMetaData table, String backendType)
   {
      Map<String, Object> d = new LinkedHashMap<>();
      d.put("name", table.getName());
      d.put("primaryKey", table.getPrimaryKeyField());
      d.put("backendType", backendType);

      Map<String, Object> fields = new TreeMap<>();
      for(QFieldMetaData field : new TreeMap<>(table.getFields()).values())
      {
         Map<String, Object> f = new LinkedHashMap<>();
         f.put("type", field.getType().name());
         f.put("required", field.getIsRequired());
         f.put("maxLength", field.getMaxLength());
         f.put("pvs", field.getPossibleValueSourceName());
         f.put("heavy", field.getIsHeavy());
         f.put("sensitive", field.getIsSensitive());
         fields.put(field.getName(), f);
      }
      d.put("fields", fields);

      List<String> uniqueKeys = new ArrayList<>();
      if(table.getUniqueKeys() != null)
      {
         for(UniqueKey key : table.getUniqueKeys())
         {
            uniqueKeys.add(String.join("+", key.getFieldNames().stream().sorted().toList()));
         }
      }
      uniqueKeys.sort(String::compareTo);
      d.put("uniqueKeys", uniqueKeys);

      List<String> associations = new ArrayList<>();
      if(table.getAssociations() != null)
      {
         for(Association association : table.getAssociations())
         {
            associations.add(association.getName() + "->" + association.getAssociatedTableName());
         }
      }
      associations.sort(String::compareTo);
      d.put("associations", associations);
      return (d);
   }



   /*******************************************************************************
    ** The fingerprint of a table: sha256:hex of the canonical descriptor.
    *******************************************************************************/
   public static String of(QTableMetaData table, String backendType)
   {
      return (fingerprintOf(descriptor(table, backendType)));
   }



   /*******************************************************************************
    ** The fingerprint of a descriptor (maps sorted by key at every level, no whitespace).
    *******************************************************************************/
   public static String fingerprintOf(Map<String, Object> descriptor)
   {
      StringBuilder sb = new StringBuilder();
      canonical(descriptor, sb);
      return ("sha256:" + HashingOutputStream.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)));
   }



   /*******************************************************************************
    ** The fingerprint of a whole instance's tables: a hash of the table fingerprints.
    *******************************************************************************/
   public static String combine(List<String> tableFingerprints)
   {
      return ("sha256:" + HashingOutputStream.sha256Hex(String.join("\n", tableFingerprints).getBytes(StandardCharsets.UTF_8)));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @SuppressWarnings("unchecked")
   private static void canonical(Object o, StringBuilder sb)
   {
      if(o == null)
      {
         sb.append("null");
      }
      else if(o instanceof Map<?, ?> map)
      {
         sb.append('{');
         boolean first = true;
         for(Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) map).entrySet())
         {
            if(!first)
            {
               sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            canonical(e.getValue(), sb);
         }
         sb.append('}');
      }
      else if(o instanceof List<?> list)
      {
         sb.append('[');
         for(int i = 0; i < list.size(); i++)
         {
            if(i > 0)
            {
               sb.append(',');
            }
            canonical(list.get(i), sb);
         }
         sb.append(']');
      }
      else if(o instanceof Number || o instanceof Boolean)
      {
         sb.append(o);
      }
      else
      {
         sb.append('"').append(String.valueOf(o).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
      }
   }
}
