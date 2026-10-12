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

package com.kingsrook.qbits.backups.scope;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QSupplementalTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Supplemental table meta-data: the backup role of a table, and the fields
 ** that reference files in object storage (when the app does not register them
 ** through the object-storage reference providers).
 *******************************************************************************/
public class BackupTableMetaData extends QSupplementalTableMetaData
{
   public static final String TYPE = "backups";

   private TableRole    role;
   private List<String> fileReferenceFields = new ArrayList<>();
   private String       fileBucketAlias;



   /*******************************************************************************
    ** The BackupTableMetaData on a table, or null.
    *******************************************************************************/
   public static BackupTableMetaData of(QTableMetaData table)
   {
      return (table == null ? null : QSupplementalTableMetaData.of(table, TYPE));
   }



   /*******************************************************************************
    ** The BackupTableMetaData on a table, adding an empty one if needed.
    *******************************************************************************/
   public static BackupTableMetaData ofOrWithNew(QTableMetaData table)
   {
      return (QSupplementalTableMetaData.ofOrWithNew(table, TYPE, BackupTableMetaData::new));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String getType()
   {
      return (TYPE);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   protected QSupplementalTableMetaData finishClone(QSupplementalTableMetaData abstractClone)
   {
      BackupTableMetaData clone = (BackupTableMetaData) abstractClone;
      clone.role = this.role;
      clone.fileReferenceFields = new ArrayList<>(this.fileReferenceFields);
      clone.fileBucketAlias = this.fileBucketAlias;
      return (clone);
   }



   /*******************************************************************************
    ** Getter for role
    *******************************************************************************/
   public TableRole getRole()
   {
      return (role);
   }



   /*******************************************************************************
    ** Fluent setter for role
    *******************************************************************************/
   public BackupTableMetaData withRole(TableRole role)
   {
      this.role = role;
      return (this);
   }



   /*******************************************************************************
    ** Getter for fileReferenceFields (fields holding a storage key in the source bucket)
    *******************************************************************************/
   public List<String> getFileReferenceFields()
   {
      return (fileReferenceFields);
   }



   /*******************************************************************************
    ** Fluent setter for fileReferenceFields
    *******************************************************************************/
   public BackupTableMetaData withFileReferenceFields(List<String> fileReferenceFields)
   {
      this.fileReferenceFields = fileReferenceFields;
      return (this);
   }



   /*******************************************************************************
    ** Getter for fileBucketAlias (the bucket the fileReferenceFields keys live in)
    *******************************************************************************/
   public String getFileBucketAlias()
   {
      return (fileBucketAlias);
   }



   /*******************************************************************************
    ** Fluent setter for fileBucketAlias
    *******************************************************************************/
   public BackupTableMetaData withFileBucketAlias(String fileBucketAlias)
   {
      this.fileBucketAlias = fileBucketAlias;
      return (this);
   }
}
