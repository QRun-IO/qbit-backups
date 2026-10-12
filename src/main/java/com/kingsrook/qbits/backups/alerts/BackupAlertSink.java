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

package com.kingsrook.qbits.backups.alerts;


import java.util.Map;


/*******************************************************************************
 ** Where backups raises and resolves alerts.  The adapter for the Alerts qbit
 ** producer SPI binds here when that qbit is installed; otherwise the no-op
 ** sink is used and every condition is still visible on the Backups home and in
 ** the tables.  An alert has a stable dedupe key; counts and links only, never
 ** values, keys or presigned URLs.
 *******************************************************************************/
public interface BackupAlertSink
{

   /***************************************************************************
    ** Raise (or refresh) an alert.
    **
    ** @param type alert type, e.g. backups.plan.missed
    ** @param dedupeKey stable key, type plus plan or run
    ** @param severity severity
    ** @param title short title
    ** @param detail counts and context only
    ***************************************************************************/
   void raise(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail);


   /***************************************************************************
    ** Resolve an alert that was raised under the dedupe key.
    ***************************************************************************/
   void resolve(String type, String dedupeKey);


   /***************************************************************************
    ** Check in a heartbeat monitor: the plan ran successfully.
    ***************************************************************************/
   void heartbeat(String monitorKey, long expectedEverySeconds, long graceSeconds);



   /***************************************************************************
    ** Severities.
    ***************************************************************************/
   enum Severity
   {
      INFO, WARNING, ERROR, CRITICAL
   }
}
