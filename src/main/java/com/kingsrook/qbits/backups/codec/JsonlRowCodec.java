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

package com.kingsrook.qbits.backups.codec;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** The JSON Lines row format (spec 8.2): one JSON object per line, keys are field
 ** names (sorted), values are the raw canonical encodings, never display values.
 ** The text of a line is canonical, so the same rows always give the same bytes
 ** (which is what the rowsHash is computed over).
 *******************************************************************************/
public class JsonlRowCodec implements RowCodec
{
   private static final ObjectMapper MAPPER = new ObjectMapper();

   private final QTableMetaData   table;
   private final List<String>     fieldNames;



   /*******************************************************************************
    ** Constructor.  The fields are the table fields, minus any excluded, sorted by name.
    *******************************************************************************/
   public JsonlRowCodec(QTableMetaData table, java.util.Collection<String> excludedFields)
   {
      this.table = table;
      TreeSet<String> names = new TreeSet<>(table.getFields().keySet());
      if(excludedFields != null)
      {
         names.removeAll(excludedFields);
      }
      this.fieldNames = new ArrayList<>(names);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<String> getFieldNames()
   {
      return (fieldNames);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String header()
   {
      return (null);
   }



   /*******************************************************************************
    ** The canonical line (no trailing newline) for a record.
    *******************************************************************************/
   @Override
   public String encode(QRecord record)
   {
      StringBuilder sb    = new StringBuilder("{");
      boolean       first = true;
      for(String name : fieldNames)
      {
         QFieldType type  = table.getField(name).getType();
         Serializable value = record.getValue(name);
         if(!first)
         {
            sb.append(',');
         }
         first = false;
         appendString(sb, name);
         sb.append(':');
         if(value == null)
         {
            sb.append("null");
         }
         else
         {
            String text = FieldValueCodec.toCanonicalText(type, value);
            if(FieldValueCodec.isBareJson(type))
            {
               sb.append(text);
            }
            else
            {
               appendString(sb, text);
            }
         }
      }
      return (sb.append('}').toString());
   }



   /*******************************************************************************
    ** Parse one line into a record.  Unknown keys are kept as strings so that
    ** restore can report them as unmapped.
    *******************************************************************************/
   @Override
   public QRecord decode(String line) throws QException
   {
      try
      {
         JsonNode              node   = MAPPER.readTree(line);
         Map<String, Serializable> values = new LinkedHashMap<>();
         for(var it = node.fields(); it.hasNext(); )
         {
            var            entry = it.next();
            QFieldMetaData field = table.getFields().get(entry.getKey());
            if(field == null)
            {
               values.put(entry.getKey(), entry.getValue().isNull() ? null : entry.getValue().asText());
            }
            else
            {
               values.put(entry.getKey(), FieldValueCodec.fromJson(field.getType(), entry.getValue()));
            }
         }
         QRecord record = new QRecord();
         record.setTableName(table.getName());
         record.setValues(values);
         return (record);
      }
      catch(Exception e)
      {
         throw (new QException("Could not read a backup row for table " + table.getName() + ": " + e.getMessage()));
      }
   }



   /*******************************************************************************
    ** Append a JSON string with escapes.  Control characters, quotes and backslashes
    ** are escaped; everything else (including non-ASCII) is written as is, in UTF-8.
    *******************************************************************************/
   static void appendString(StringBuilder sb, String s)
   {
      sb.append('"');
      for(int i = 0; i < s.length(); i++)
      {
         char c = s.charAt(i);
         switch(c)
         {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case '\b' -> sb.append("\\b");
            case '\f' -> sb.append("\\f");
            default ->
            {
               if(c < 0x20)
               {
                  sb.append(String.format("\\u%04x", (int) c));
               }
               else
               {
                  sb.append(c);
               }
            }
         }
      }
      sb.append('"');
   }
}
