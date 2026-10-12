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

package com.kingsrook.qbits.backups.files;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.backups.scope.BackupTableMetaData;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Finds the files a record references.  Apps declare their file references once
 ** (the object-storage qbit StorageReferenceProvider registrations, which an
 ** adapter exposes here); the default provider reads the fileReferenceFields of
 ** the table BackupTableMetaData.
 *******************************************************************************/
public interface FileReferenceProvider
{

   /***************************************************************************
    ** The files this record references (empty when none).
    ***************************************************************************/
   List<FileReference> referencesIn(QTableMetaData table, QRecord record);



   /***************************************************************************
    ** Reads BackupTableMetaData.fileReferenceFields: each named field holds a key
    ** in the table fileBucketAlias bucket.
    ***************************************************************************/
   class FromTableMetaData implements FileReferenceProvider
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public List<FileReference> referencesIn(QTableMetaData table, QRecord record)
      {
         BackupTableMetaData meta = BackupTableMetaData.of(table);
         if(meta == null || meta.getFileReferenceFields().isEmpty())
         {
            return (List.of());
         }
         List<FileReference> rs = new ArrayList<>();
         for(String field : meta.getFileReferenceFields())
         {
            String key = record.getValueString(field);
            if(key != null && !key.isBlank())
            {
               rs.add(new FileReference(table.getName(), String.valueOf(record.getValue(table.getPrimaryKeyField())), field, meta.getFileBucketAlias(), key, null, null));
            }
         }
         return (rs);
      }
   }
}
