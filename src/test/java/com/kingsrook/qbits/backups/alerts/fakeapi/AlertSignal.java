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

package com.kingsrook.qbits.backups.alerts.fakeapi;


import java.util.LinkedHashMap;
import java.util.Map;


/*******************************************************************************
 ** Test double of the Alerts producer SPI AlertSignal (only what the adapter uses).
 *******************************************************************************/
public class AlertSignal
{
   public final String              alertTypeName;
   public       String              dedupKey;
   public       AlertSeverity       severity;
   public       String              title;
   public       String              summary;
   public final Map<String, String> attributes = new LinkedHashMap<>();



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public AlertSignal(String alertTypeName)
   {
      this.alertTypeName = alertTypeName;
   }



   /*******************************************************************************
    ** Setter.
    *******************************************************************************/
   public void setDedupKey(String dedupKey)
   {
      this.dedupKey = dedupKey;
   }



   /*******************************************************************************
    ** Setter.
    *******************************************************************************/
   public void setSeverity(AlertSeverity severity)
   {
      this.severity = severity;
   }



   /*******************************************************************************
    ** Setter.
    *******************************************************************************/
   public void setTitle(String title)
   {
      this.title = title;
   }



   /*******************************************************************************
    ** Setter.
    *******************************************************************************/
   public void setSummary(String summary)
   {
      this.summary = summary;
   }



   /*******************************************************************************
    ** Fluent attribute.
    *******************************************************************************/
   public AlertSignal withAttribute(String key, String value)
   {
      attributes.put(key, value);
      return (this);
   }
}
