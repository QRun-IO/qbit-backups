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

package com.kingsrook.qbits.backups.storage.objectstorage;


import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.BackupStorageException;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qbits.objectstorage.api.Capability;
import com.kingsrook.qbits.objectstorage.api.CapabilityStatus;
import com.kingsrook.qbits.objectstorage.api.CopyRequest;
import com.kingsrook.qbits.objectstorage.api.DeleteMode;
import com.kingsrook.qbits.objectstorage.api.DeleteRequest;
import com.kingsrook.qbits.objectstorage.api.ListRequest;
import com.kingsrook.qbits.objectstorage.api.LockMode;
import com.kingsrook.qbits.objectstorage.api.ObjectStorage;
import com.kingsrook.qbits.objectstorage.api.ObjectStream;
import com.kingsrook.qbits.objectstorage.api.ObjectSummary;
import com.kingsrook.qbits.objectstorage.api.Page;
import com.kingsrook.qbits.objectstorage.api.PresignGet;
import com.kingsrook.qbits.objectstorage.api.PutRequest;
import com.kingsrook.qbits.objectstorage.api.Range;
import com.kingsrook.qbits.objectstorage.api.StorageBucket;
import com.kingsrook.qbits.objectstorage.api.StorageException;
import com.kingsrook.qbits.objectstorage.api.StoredObject;


/*******************************************************************************
 ** BackupStorage over a qbit-object-storage StorageBucket.  Backups never builds
 ** an S3 client and never holds a credential: every byte goes through the
 ** storage qbit, in a system context tagged with the backups caller name (so
 ** the object_storage_operation log shows "system: backups run ...").
 **
 ** A write of unknown length is bridged from an OutputStream to the put of an
 ** InputStream (the storage qbit uses multipart above its threshold, so memory
 ** stays bounded); closing the stream waits for the put and rethrows its failure.
 ** Permanent delete uses DeleteRequest.permanent() (system callers only), and a
 ** locked object comes back as LOCKED.
 **
 ** This class is optional: it only loads when qbit-object-storage is on the classpath.
 *******************************************************************************/
public class ObjectStorageBackupStorage implements BackupStorage
{
   private final StorageBucket bucket;



   /*******************************************************************************
    ** Constructor over a bucket (already in the right user or system context).
    *******************************************************************************/
   public ObjectStorageBackupStorage(StorageBucket bucket)
   {
      this.bucket = bucket;
   }



   /*******************************************************************************
    ** A resolver (bucket alias to storage) that talks to the storage qbit as the
    ** system caller "backups", tagged with the given run id (any text).
    *******************************************************************************/
   public static Function<String, BackupStorage> systemResolver(String runId)
   {
      return (alias -> new ObjectStorageBackupStorage(ObjectStorage.asSystem("backups", runId).bucket(alias)));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public OutputStream openWrite(String key, WriteOptions options)
   {
      try
      {
         PipedInputStream  in  = new PipedInputStream(1 << 20);
         PipedOutputStream out = new PipedOutputStream(in);

         PutRequest request = PutRequest.of(key, in).withContentType(options.getContentType()).withMetadata(new LinkedHashMap<>(options.getMetadata()));
         if(options.getObjectLockMode() != null)
         {
            request.withObjectLock(LockMode.valueOf(options.getObjectLockMode()), options.getRetainUntil());
         }

         CompletableFuture<Void> put = CompletableFuture.runAsync(() ->
         {
            try
            {
               bucket.put(request);
            }
            finally
            {
               try
               {
                  in.close();
               }
               catch(IOException e)
               {
                  // nothing more to do: the writer side sees the broken pipe
               }
            }
         });

         return (new OutputStream()
         {
            /*******************************************************************************
             **
             *******************************************************************************/
            @Override
            public void write(int b) throws IOException
            {
               ensureWriterAlive();
               out.write(b);
            }



            /*******************************************************************************
             **
             *******************************************************************************/
            @Override
            public void write(byte[] b, int off, int len) throws IOException
            {
               ensureWriterAlive();
               try
               {
                  out.write(b, off, len);
               }
               catch(IOException e)
               {
                  throw (failureOr(e));
               }
            }



            /*******************************************************************************
             **
             *******************************************************************************/
            @Override
            public void flush() throws IOException
            {
               out.flush();
            }



            /*******************************************************************************
             ** Close the pipe, then wait for the put to finish, rethrowing its failure.
             *******************************************************************************/
            @Override
            public void close() throws IOException
            {
               try
               {
                  out.close();
                  put.join();
               }
               catch(java.util.concurrent.CompletionException e)
               {
                  throw (asIOException(e.getCause()));
               }
            }



            /*******************************************************************************
             **
             *******************************************************************************/
            private void ensureWriterAlive() throws IOException
            {
               if(put.isCompletedExceptionally())
               {
                  try
                  {
                     put.join();
                  }
                  catch(java.util.concurrent.CompletionException e)
                  {
                     throw (asIOException(e.getCause()));
                  }
               }
            }



            /*******************************************************************************
             **
             *******************************************************************************/
            private IOException failureOr(IOException original) throws IOException
            {
               try
               {
                  put.join();
               }
               catch(java.util.concurrent.CompletionException e)
               {
                  return (asIOException(e.getCause()));
               }
               return (original);
            }
         });
      }
      catch(IOException e)
      {
         throw (new BackupStorageException(BackupStorageException.Reason.PROVIDER_ERROR, "Could not open a write stream for " + key, e));
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static IOException asIOException(Throwable cause)
   {
      if(cause instanceof StorageException se)
      {
         return (new IOException(convert(se).getMessage(), convert(se)));
      }
      return (cause instanceof IOException io ? io : new IOException(String.valueOf(cause), cause));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public InputStream openRead(String key)
   {
      try
      {
         ObjectStream stream = bucket.get(key, Range.all());
         return (new java.io.FilterInputStream(stream.getInputStream())
         {
            /*******************************************************************************
             **
             *******************************************************************************/
            @Override
            public void close() throws IOException
            {
               try
               {
                  super.close();
               }
               finally
               {
                  stream.close();
               }
            }
         });
      }
      catch(StorageException e)
      {
         throw (convert(e));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public Optional<StoredObjectInfo> head(String key)
   {
      try
      {
         return (bucket.head(key).map(ObjectStorageBackupStorage::info));
      }
      catch(StorageException e)
      {
         throw (convert(e));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public List<StoredObjectInfo> list(String prefix)
   {
      try
      {
         List<StoredObjectInfo> rs    = new ArrayList<>();
         String                 after = null;
         do
         {
            Page<ObjectSummary> page = bucket.list(ListRequest.prefix(prefix == null ? "" : prefix).pageSize(1000).after(after));
            for(ObjectSummary summary : page.getItems())
            {
               rs.add(new StoredObjectInfo(summary.getKey(), summary.getSize() == null ? 0 : summary.getSize(), null, new LinkedHashMap<>(), null));
            }
            after = page.getNextToken();
         }
         while(after != null);
         return (rs);
      }
      catch(StorageException e)
      {
         throw (convert(e));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void copy(String sourceKey, String destinationKey)
   {
      try
      {
         bucket.copy(CopyRequest.of(sourceKey, destinationKey));
      }
      catch(StorageException e)
      {
         throw (convert(e));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public DeleteResult deletePermanent(String key)
   {
      try
      {
         DeleteMode mode = bucket.delete(DeleteRequest.of(key).permanent()).getMode();
         return (switch(mode)
         {
            case SKIPPED_OBJECT_LOCK -> DeleteResult.LOCKED;
            case NOT_FOUND -> DeleteResult.NOT_FOUND;
            default -> DeleteResult.DELETED;
         });
      }
      catch(StorageException e)
      {
         throw (convert(e));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String presignGet(String key, Duration ttl, String downloadName)
   {
      try
      {
         return (bucket.presignGet(PresignGet.of(key).withTtl(ttl).withDownloadAs(downloadName)).toString());
      }
      catch(StorageException e)
      {
         throw (convert(e));
      }
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public Set<StorageCapability> capabilities()
   {
      Set<StorageCapability> rs = EnumSet.of(StorageCapability.SERVER_SIDE_COPY);
      if(bucket.capability(Capability.OBJECT_LOCK) == CapabilityStatus.SUPPORTED)
      {
         rs.add(StorageCapability.OBJECT_LOCK);
      }
      if(bucket.capability(Capability.VERSIONING) == CapabilityStatus.SUPPORTED || bucket.isVersioned())
      {
         rs.add(StorageCapability.VERSIONING);
      }
      if(bucket.capability(Capability.MULTIPART) == CapabilityStatus.SUPPORTED)
      {
         rs.add(StorageCapability.MULTIPART);
      }
      return (rs);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static StoredObjectInfo info(StoredObject object)
   {
      java.time.Instant lockedUntil = object.getLockRetainUntil();
      return (new StoredObjectInfo(object.getKey(), object.getSize() == null ? 0 : object.getSize(), object.getLastModified(),
         object.getHeaders() == null || object.getHeaders().getUserMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(object.getHeaders().getUserMetadata()), lockedUntil));
   }



   /*******************************************************************************
    ** Map a storage qbit failure onto a BackupStorageException.
    *******************************************************************************/
   static BackupStorageException convert(StorageException e)
   {
      BackupStorageException.Reason reason = switch(e.getReason())
      {
         case NOT_FOUND -> BackupStorageException.Reason.NOT_FOUND;
         case ACCESS_DENIED -> BackupStorageException.Reason.ACCESS_DENIED;
         case UNSUPPORTED -> BackupStorageException.Reason.UNSUPPORTED;
         case PRECONDITION_FAILED -> BackupStorageException.Reason.PRECONDITION_FAILED;
         case THROTTLED -> BackupStorageException.Reason.THROTTLED;
         default -> BackupStorageException.Reason.PROVIDER_ERROR;
      };
      return (new BackupStorageException(reason, e.getMessage() + (e.getRequestId() == null ? "" : " (request " + e.getRequestId() + ")"), e));
   }
}
