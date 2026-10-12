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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import com.fasterxml.jackson.databind.JsonNode;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.utils.ValueUtils;


/*******************************************************************************
 ** The canonical, lossless text encoding of a field value, by QFieldType
 ** (spec 8.2).  The same canonical text is used by JSONL and CSV, so a value
 ** means the same thing in both, and by the content hash.
 **
 **  STRING, TEXT, HTML, PASSWORD: string
 **  INTEGER, LONG: number
 **  DECIMAL: string (BigDecimal.toPlainString), so there is no float rounding
 **  BOOLEAN: true or false
 **  DATE: yyyy-MM-dd
 **  TIME: HH:mm:ss, with .SSSSSS only when there are fractional seconds
 **  DATE_TIME: an ISO-8601 instant in UTC, with .SSSSSS only when there are fractional seconds
 **  BLOB: base64
 *******************************************************************************/
public final class FieldValueCodec
{
   private static final DateTimeFormatter TIME_FORMAT          = DateTimeFormatter.ofPattern("HH:mm:ss");
   private static final DateTimeFormatter TIME_FORMAT_MICROS   = DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS");
   private static final DateTimeFormatter INSTANT_FORMAT       = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);
   private static final DateTimeFormatter INSTANT_FORMAT_MICRO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private FieldValueCodec()
   {
   }



   /*******************************************************************************
    ** Whether the canonical JSON form of a type is a bare number or boolean (not quoted).
    *******************************************************************************/
   public static boolean isBareJson(QFieldType type)
   {
      return (type == QFieldType.INTEGER || type == QFieldType.LONG || type == QFieldType.BOOLEAN);
   }



   /*******************************************************************************
    ** The canonical text of a non-null value.
    *******************************************************************************/
   public static String toCanonicalText(QFieldType type, Serializable value)
   {
      switch(type)
      {
         case INTEGER, LONG:
            return (String.valueOf(ValueUtils.getValueAsLong(value)));
         case DECIMAL:
            return (ValueUtils.getValueAsBigDecimal(value).toPlainString());
         case BOOLEAN:
            return (String.valueOf(ValueUtils.getValueAsBoolean(value)));
         case DATE:
            return (ValueUtils.getValueAsLocalDate(value).toString());
         case TIME:
         {
            LocalTime time = ValueUtils.getValueAsLocalTime(value);
            return (time.getNano() == 0 ? TIME_FORMAT.format(time) : TIME_FORMAT_MICROS.format(time));
         }
         case DATE_TIME:
         {
            Instant instant = ValueUtils.getValueAsInstant(value);
            return (instant.getNano() == 0 ? INSTANT_FORMAT.format(instant) : INSTANT_FORMAT_MICRO.format(instant));
         }
         case BLOB:
            return (Base64.getEncoder().encodeToString(blobBytes(value)));
         default:
            return (String.valueOf(value));
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static byte[] blobBytes(Serializable value)
   {
      if(value instanceof byte[] bytes)
      {
         return (bytes);
      }
      return (String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   }



   /*******************************************************************************
    ** Parse canonical text back to a Java value.
    *******************************************************************************/
   public static Serializable fromCanonicalText(QFieldType type, String text)
   {
      if(text == null)
      {
         return (null);
      }
      switch(type)
      {
         case INTEGER:
            return (Integer.valueOf(text));
         case LONG:
            return (Long.valueOf(text));
         case DECIMAL:
            return (new BigDecimal(text));
         case BOOLEAN:
            return (Boolean.valueOf(text));
         case DATE:
            return (LocalDate.parse(text));
         case TIME:
            return (LocalTime.parse(text));
         case DATE_TIME:
            return (Instant.parse(text));
         case BLOB:
            return (Base64.getDecoder().decode(text));
         default:
            return (text);
      }
   }



   /*******************************************************************************
    ** Convert a parsed JSON value to a Java value.
    *******************************************************************************/
   public static Serializable fromJson(QFieldType type, JsonNode node)
   {
      if(node == null || node.isNull())
      {
         return (null);
      }
      if(type == QFieldType.BOOLEAN)
      {
         return (node.asBoolean());
      }
      return (fromCanonicalText(type, node.isNumber() ? node.asText() : node.textValue()));
   }
}
