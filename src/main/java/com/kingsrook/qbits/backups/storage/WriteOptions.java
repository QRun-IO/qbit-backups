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

package com.kingsrook.qbits.backups.storage;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;


/*******************************************************************************
 ** Options for writing one object.
 *******************************************************************************/
public class WriteOptions
{
   private String              contentType = "application/octet-stream";
   private Map<String, String> metadata    = new LinkedHashMap<>();
   private String              objectLockMode;
   private Instant             retainUntil;



   /*******************************************************************************
    ** Start with defaults.
    *******************************************************************************/
   public static WriteOptions create()
   {
      return (new WriteOptions());
   }



   /*******************************************************************************
    ** Getter for contentType
    *******************************************************************************/
   public String getContentType()
   {
      return (contentType);
   }



   /*******************************************************************************
    ** Fluent setter for contentType
    *******************************************************************************/
   public WriteOptions withContentType(String contentType)
   {
      this.contentType = contentType;
      return (this);
   }



   /*******************************************************************************
    ** Getter for metadata (user metadata, which describes the object even without the catalog)
    *******************************************************************************/
   public Map<String, String> getMetadata()
   {
      return (metadata);
   }



   /*******************************************************************************
    ** Add one metadata entry.
    *******************************************************************************/
   public WriteOptions withMetadata(String key, String value)
   {
      this.metadata.put(key, value);
      return (this);
   }



   /*******************************************************************************
    ** Getter for objectLockMode (GOVERNANCE or COMPLIANCE), null for none
    *******************************************************************************/
   public String getObjectLockMode()
   {
      return (objectLockMode);
   }



   /*******************************************************************************
    ** Getter for retainUntil
    *******************************************************************************/
   public Instant getRetainUntil()
   {
      return (retainUntil);
   }



   /*******************************************************************************
    ** Request an object lock on the written object.
    *******************************************************************************/
   public WriteOptions withObjectLock(String mode, Instant retainUntil)
   {
      this.objectLockMode = mode;
      this.retainUntil = retainUntil;
      return (this);
   }
}
