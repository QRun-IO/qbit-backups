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
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** The CSV row format (spec 8.3): RFC 4180, UTF-8 without a BOM, a header of
 ** field names, and the same canonical value text as JSONL.  Null is written as
 ** \N (unquoted) so it can be told from the empty string; a string that is
 ** literally \N is quoted.  A multi-line value is one record in a quoted field,
 ** so the codec works on records (a record may span lines): encode() returns a
 ** text that may contain embedded newlines, and the part writer appends one
 ** newline after each.
 *******************************************************************************/
public class CsvRowCodec implements RowCodec
{
   public static final String NULL_TOKEN = "\\N";

   private final QTableMetaData table;
   private final List<String>   fieldNames;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public CsvRowCodec(QTableMetaData table, java.util.Collection<String> excludedFields)
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
      return (String.join(",", fieldNames.stream().map(CsvRowCodec::quoteIfNeeded).toList()));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String encode(QRecord record)
   {
      StringBuilder sb = new StringBuilder();
      for(int i = 0; i < fieldNames.size(); i++)
      {
         if(i > 0)
         {
            sb.append(',');
         }
         String       name  = fieldNames.get(i);
         Serializable value = record.getValue(name);
         if(value == null)
         {
            sb.append(NULL_TOKEN);
         }
         else
         {
            String text = FieldValueCodec.toCanonicalText(table.getField(name).getType(), value);
            sb.append(NULL_TOKEN.equals(text) ? "\"" + text + "\"" : quoteIfNeeded(text));
         }
      }
      return (sb.toString());
   }



   /*******************************************************************************
    ** Parse one CSV record (as returned by CsvRecordReader, header excluded) to a record.
    ** The line may contain embedded newlines inside quoted fields.
    *******************************************************************************/
   @Override
   public QRecord decode(String line) throws QException
   {
      List<String> cells = splitCells(line);
      return (toRecord(cells, fieldNames));
   }



   /*******************************************************************************
    ** Build a record from already-split cells and the header names.
    *******************************************************************************/
   public QRecord toRecord(List<String> cells, List<String> header) throws QException
   {
      if(cells.size() != header.size())
      {
         throw (new QException("CSV row has " + cells.size() + " cells but the header has " + header.size() + " for table " + table.getName()));
      }
      Map<String, Serializable> values = new LinkedHashMap<>();
      for(int i = 0; i < header.size(); i++)
      {
         String         cell  = cells.get(i);
         QFieldMetaData field = table.getFields().get(header.get(i));
         if(cell == null)
         {
            values.put(header.get(i), null);
         }
         else if(field == null)
         {
            values.put(header.get(i), cell);
         }
         else
         {
            values.put(header.get(i), FieldValueCodec.fromCanonicalText(field.getType(), cell));
         }
      }
      QRecord record = new QRecord();
      record.setTableName(table.getName());
      record.setValues(values);
      return (record);
   }



   /*******************************************************************************
    ** Quote a cell when it contains a comma, quote, CR or LF (or is empty after
    ** being a header, which never happens), doubling embedded quotes.
    *******************************************************************************/
   static String quoteIfNeeded(String s)
   {
      if(s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0)
      {
         return ("\"" + s.replace("\"", "\"\"") + "\"");
      }
      return (s);
   }



   /*******************************************************************************
    ** Split one CSV record into cells.  Returns null for an unquoted \N cell (a null).
    *******************************************************************************/
   public static List<String> splitCells(String line)
   {
      List<String>  cells  = new ArrayList<>();
      StringBuilder cell   = new StringBuilder();
      boolean       quoted = false;
      boolean       wasQuoted = false;
      for(int i = 0; i < line.length(); i++)
      {
         char c = line.charAt(i);
         if(quoted)
         {
            if(c == '"')
            {
               if(i + 1 < line.length() && line.charAt(i + 1) == '"')
               {
                  cell.append('"');
                  i++;
               }
               else
               {
                  quoted = false;
               }
            }
            else
            {
               cell.append(c);
            }
         }
         else if(c == '"')
         {
            quoted = true;
            wasQuoted = true;
         }
         else if(c == ',')
         {
            cells.add(finishCell(cell, wasQuoted));
            cell = new StringBuilder();
            wasQuoted = false;
         }
         else
         {
            cell.append(c);
         }
      }
      cells.add(finishCell(cell, wasQuoted));
      return (cells);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static String finishCell(StringBuilder cell, boolean wasQuoted)
   {
      String s = cell.toString();
      return (!wasQuoted && NULL_TOKEN.equals(s) ? null : s);
   }



   /*******************************************************************************
    ** Split a whole CSV text into records, honoring newlines inside quoted fields.
    *******************************************************************************/
   public static List<String> splitRecords(String text)
   {
      List<String>  records = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      boolean       quoted  = false;
      for(int i = 0; i < text.length(); i++)
      {
         char c = text.charAt(i);
         if(c == '"')
         {
            quoted = !quoted;
            current.append(c);
         }
         else if(c == '\n' && !quoted)
         {
            records.add(current.toString());
            current = new StringBuilder();
         }
         else
         {
            current.append(c);
         }
      }
      if(current.length() > 0)
      {
         records.add(current.toString());
      }
      return (records);
   }
}
