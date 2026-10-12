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
 ** The sink used when the Alerts qbit is not installed.
 *******************************************************************************/
public class NoOpBackupAlertSink implements BackupAlertSink
{

   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void raise(String type, String dedupeKey, Severity severity, String title, Map<String, String> detail)
   {
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void resolve(String type, String dedupeKey)
   {
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void heartbeat(String monitorKey, long expectedEverySeconds, long graceSeconds)
   {
   }
}
