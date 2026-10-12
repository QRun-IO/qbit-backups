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


import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;


/*******************************************************************************
 ** The narrow storage API that the Backups qbit needs.  It mirrors the
 ** qbit-object-storage bucket API (put, get, head, list, copy, delete, presign),
 ** so the production implementation is a thin adapter over a StorageBucket, and
 ** tests use InMemoryBackupStorage.  Keys are relative to the bucket.
 **
 ** All methods throw BackupStorageException (a RuntimeException) on failure.
 *******************************************************************************/
public interface BackupStorage
{

   /***************************************************************************
    ** Open a stream to write an object of unknown length.  The object becomes
    ** visible when the stream is closed successfully.
    ***************************************************************************/
   OutputStream openWrite(String key, WriteOptions options);


   /***************************************************************************
    ** Open a stream over an object.
    ***************************************************************************/
   InputStream openRead(String key);


   /***************************************************************************
    ** Existence and size.
    ***************************************************************************/
   Optional<StoredObjectInfo> head(String key);


   /***************************************************************************
    ** All objects with the given key prefix (recursive, in key order).
    ***************************************************************************/
   List<StoredObjectInfo> list(String prefix);


   /***************************************************************************
    ** Server-side copy within the same bucket (or connection).
    ***************************************************************************/
   void copy(String sourceKey, String destinationKey);


   /***************************************************************************
    ** Permanently delete an object, every version.  Object-locked objects are
    ** not deleted (DeleteResult.LOCKED); a missing object is NOT_FOUND.
    ***************************************************************************/
   DeleteResult deletePermanent(String key);


   /***************************************************************************
    ** A time-limited download link.
    ***************************************************************************/
   String presignGet(String key, Duration ttl, String downloadName);


   /***************************************************************************
    ** What the bucket can do (object lock, server-side copy, ...).
    ***************************************************************************/
   Set<StorageCapability> capabilities();



   /***************************************************************************
    ** Result of a permanent delete.
    ***************************************************************************/
   enum DeleteResult
   {
      DELETED, NOT_FOUND, LOCKED
   }



   /***************************************************************************
    ** Capabilities, as probed by the storage qbit.
    ***************************************************************************/
   enum StorageCapability
   {
      OBJECT_LOCK, VERSIONING, SERVER_SIDE_COPY, MULTIPART
   }
}
