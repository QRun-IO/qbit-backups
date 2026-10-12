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


import java.util.ArrayList;
import java.util.List;


/*******************************************************************************
 ** Test double of the Alerts producer SPI QAlerts: static calls that record what
 ** they were given.
 *******************************************************************************/
public final class QAlerts
{
   public static final List<AlertSignal> RAISED     = new ArrayList<>();
   public static final List<String>      RESOLVED   = new ArrayList<>();
   public static final List<String>      HEARTBEATS = new ArrayList<>();

   /** When set, every call throws it (the SPI is meant not to, but the adapter must survive). */
   public static RuntimeException failWith;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private QAlerts()
   {
   }



   /*******************************************************************************
    ** Forget everything.
    *******************************************************************************/
   public static void reset()
   {
      RAISED.clear();
      RESOLVED.clear();
      HEARTBEATS.clear();
      failWith = null;
   }



   /*******************************************************************************
    ** Raise.
    *******************************************************************************/
   public static void raise(AlertSignal signal)
   {
      maybeFail();
      RAISED.add(signal);
   }



   /*******************************************************************************
    ** Resolve.
    *******************************************************************************/
   public static void resolve(String alertTypeName, String dedupKey, String note)
   {
      maybeFail();
      RESOLVED.add(alertTypeName + "|" + dedupKey + "|" + note);
   }



   /*******************************************************************************
    ** Heartbeat.
    *******************************************************************************/
   public static void heartbeat(String heartbeatKey)
   {
      maybeFail();
      HEARTBEATS.add(heartbeatKey);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void maybeFail()
   {
      if(failWith != null)
      {
         throw (failWith);
      }
   }
}
