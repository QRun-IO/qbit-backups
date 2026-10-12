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

package com.kingsrook.qbits.backups;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.alerts.BackupAlertSink;


/*******************************************************************************
 ** A test double for the alert sink: remembers every call, and which alerts are
 ** currently open (raised and not resolved), by dedupe key.
 *******************************************************************************/
public class RecordingAlertSink implements BackupAlertSink
{
   /** One raise call. */
   public record Raised(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail)
   {
   }

   public final List<Raised>           raises     = new ArrayList<>();
   public final List<String>           resolves   = new ArrayList<>();
   public final List<String>           heartbeats = new ArrayList<>();
   public final Map<String, Raised>    open       = new LinkedHashMap<>();



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void raise(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail)
   {
      Raised raised = new Raised(type, dedupeKey, severity, title, detail);
      raises.add(raised);
      open.put(dedupeKey, raised);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void resolve(String type, String dedupeKey)
   {
      resolves.add(dedupeKey);
      open.remove(dedupeKey);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void heartbeat(String monitorKey, long expectedEverySeconds, long graceSeconds)
   {
      heartbeats.add(monitorKey);
   }



   /*******************************************************************************
    ** Whether an alert is open.
    *******************************************************************************/
   public boolean isOpen(String dedupeKey)
   {
      return (open.containsKey(dedupeKey));
   }



   /*******************************************************************************
    ** The open alert, or null.
    *******************************************************************************/
   public Raised openAlert(String dedupeKey)
   {
      return (open.get(dedupeKey));
   }
}
