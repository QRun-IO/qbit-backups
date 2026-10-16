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

package com.kingsrook.qbits.backups.run;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import com.kingsrook.qqq.backend.core.actions.interfaces.QStorageInterface;
import com.kingsrook.qqq.backend.core.model.actions.tables.storage.StorageInput;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleDispatcher;
import com.kingsrook.qqq.backend.core.modules.backend.QBackendModuleInterface;


/*******************************************************************************
 ** A storage-only backend module (like qbit-edi's PreviewDownloadBackend): it
 ** streams objects but has no query interface, so a table on it cannot be read.
 *******************************************************************************/
public class StorageOnlyBackendModule implements QBackendModuleInterface, QStorageInterface
{
   public static final String TYPE = "test-storage-only";

   static
   {
      QBackendModuleDispatcher.registerBackendModule(new StorageOnlyBackendModule());
   }



   /*******************************************************************************
    ** The backend type.
    *******************************************************************************/
   @Override
   public String getBackendType()
   {
      return (TYPE);
   }



   /*******************************************************************************
    ** Storage only.
    *******************************************************************************/
   @Override
   public QStorageInterface getStorageInterface()
   {
      return (this);
   }



   /*******************************************************************************
    ** Nothing is stored.
    *******************************************************************************/
   @Override
   public InputStream getInputStream(StorageInput input)
   {
      return (new ByteArrayInputStream(new byte[0]));
   }



   /*******************************************************************************
    ** Writes are discarded.
    *******************************************************************************/
   @Override
   public OutputStream createOutputStream(StorageInput input)
   {
      return (new ByteArrayOutputStream());
   }
}
