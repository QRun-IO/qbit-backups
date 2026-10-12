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
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;


/*******************************************************************************
 ** Test double of the Alerts producer SPI QAlertTypeMetaData: records the types
 ** added to an instance.
 *******************************************************************************/
public class QAlertTypeMetaData
{
   public static final List<QAlertTypeMetaData> ADDED = new ArrayList<>();

   public String        name;
   public String        label;
   public String        groupName;
   public String        howToFix;
   public AlertSeverity defaultSeverity;



   /*******************************************************************************
    ** Fluent.
    *******************************************************************************/
   public QAlertTypeMetaData withName(String name)
   {
      this.name = name;
      return (this);
   }



   /*******************************************************************************
    ** Fluent.
    *******************************************************************************/
   public QAlertTypeMetaData withLabel(String label)
   {
      this.label = label;
      return (this);
   }



   /*******************************************************************************
    ** Fluent.
    *******************************************************************************/
   public QAlertTypeMetaData withGroupName(String groupName)
   {
      this.groupName = groupName;
      return (this);
   }



   /*******************************************************************************
    ** Fluent.
    *******************************************************************************/
   public QAlertTypeMetaData withHowToFix(String howToFix)
   {
      this.howToFix = howToFix;
      return (this);
   }



   /*******************************************************************************
    ** Fluent.
    *******************************************************************************/
   public QAlertTypeMetaData withDefaultSeverity(AlertSeverity defaultSeverity)
   {
      this.defaultSeverity = defaultSeverity;
      return (this);
   }



   /*******************************************************************************
    ** Add to the instance (recorded).
    *******************************************************************************/
   public void addSelfToInstance(QInstance qInstance)
   {
      ADDED.add(this);
   }
}
