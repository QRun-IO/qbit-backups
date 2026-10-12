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

package com.kingsrook.qbits.backups.events;


import java.io.Serializable;
import java.util.Map;
import com.kingsrook.qqq.esb.publish.EsbPublishAction;
import com.kingsrook.qqq.esb.publish.EsbPublishInput;


/*******************************************************************************
 ** Publishes a backup event on the QQQ ESB.  This is the only class that touches
 ** qqq-esb (an optional dependency); BackupEvents calls it inside a guard that
 ** swallows a missing class (NoClassDefFoundError), so apps without the ESB never
 ** notice.
 *******************************************************************************/
final class EsbBackupEventPublisher
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EsbBackupEventPublisher()
   {
   }



   /*******************************************************************************
    ** Publish one event to a destination.
    *******************************************************************************/
   static void publish(String destinationName, String type, Map<String, Serializable> data)
   {
      new EsbPublishAction().execute(new EsbPublishInput()
         .withDestinationName(destinationName)
         .withType(type)
         .withSourcePath("/backups")
         .withData(data));
   }
}
