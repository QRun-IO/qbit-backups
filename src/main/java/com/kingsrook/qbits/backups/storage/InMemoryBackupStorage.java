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


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;


/*******************************************************************************
 ** A BackupStorage held in memory.  It is the test implementation and a
 ** stand-in for local development.  It supports object lock, and has hooks for
 ** failure injection (a predicate over keys that makes a write fail).
 *******************************************************************************/
public class InMemoryBackupStorage implements BackupStorage
{
   private final TreeMap<String, Entry> objects = new TreeMap<>();

   private Clock                  clock = Clock.systemUTC();
   private Predicate<String>      failWritesFor;
   private Set<StorageCapability> capabilities = EnumSet.of(StorageCapability.OBJECT_LOCK, StorageCapability.SERVER_SIDE_COPY, StorageCapability.MULTIPART);

   private record Entry(byte[] bytes, Map<String, String> metadata, Instant lastModified, Instant lockedUntil)
   {
   }



   /*******************************************************************************
    ** Use a fixed clock (for object lock and last modified in tests).
    *******************************************************************************/
   public InMemoryBackupStorage withClock(Clock clock)
   {
      this.clock = clock;
      return (this);
   }



   /*******************************************************************************
    ** Make writes of matching keys fail with a provider error (failure injection).
    *******************************************************************************/
   public InMemoryBackupStorage withFailWritesFor(Predicate<String> failWritesFor)
   {
      this.failWritesFor = failWritesFor;
      return (this);
   }



   /*******************************************************************************
    ** Set the capabilities this storage reports.
    *******************************************************************************/
   public InMemoryBackupStorage withCapabilities(Set<StorageCapability> capabilities)
   {
      this.capabilities = capabilities;
      return (this);
   }



   /*******************************************************************************
    ** Test helper: remove everything.
    *******************************************************************************/
   public synchronized void clear()
   {
      objects.clear();
      failWritesFor = null;
   }



   /*******************************************************************************
    ** Test helper: put bytes directly.
    *******************************************************************************/
   public synchronized void putBytes(String key, byte[] bytes)
   {
      objects.put(key, new Entry(bytes, new LinkedHashMap<>(), clock.instant(), null));
   }



   /*******************************************************************************
    ** Test helper: read bytes directly.
    *******************************************************************************/
   public synchronized byte[] getBytes(String key)
   {
      Entry entry = objects.get(key);
      return (entry == null ? null : entry.bytes());
   }



   /*******************************************************************************
    ** Test helper: remove an object ignoring any lock (simulating an out-of-band delete).
    *******************************************************************************/
   public synchronized void removeIgnoringLock(String key)
   {
      objects.remove(key);
   }



   /*******************************************************************************
    ** Test helper: replace an object bytes (simulating corruption).
    *******************************************************************************/
   public synchronized void corrupt(String key, byte[] bytes)
   {
      Entry old = objects.get(key);
      objects.put(key, new Entry(bytes, old == null ? new LinkedHashMap<>() : old.metadata(), clock.instant(), old == null ? null : old.lockedUntil()));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public OutputStream openWrite(String key, WriteOptions options)
   {
      if(failWritesFor != null && failWritesFor.test(key))
      {
         throw (new BackupStorageException(BackupStorageException.Reason.PROVIDER_ERROR, "injected write failure for " + key));
      }
      if(options.getObjectLockMode() != null && !capabilities.contains(StorageCapability.OBJECT_LOCK))
      {
         throw (new BackupStorageException(BackupStorageException.Reason.UNSUPPORTED, "object lock is not supported"));
      }

      return (new ByteArrayOutputStream()
      {
         private boolean closed = false;



         /*******************************************************************************
          ** commit on close
          *******************************************************************************/
         @Override
         public void close() throws IOException
         {
            super.close();
            if(!closed)
            {
               closed = true;
               synchronized(InMemoryBackupStorage.this)
               {
                  objects.put(key, new Entry(toByteArray(), new LinkedHashMap<>(options.getMetadata()), clock.instant(), options.getRetainUntil()));
               }
            }
         }
      });
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public synchronized InputStream openRead(String key)
   {
      Entry entry = objects.get(key);
      if(entry == null)
      {
         throw (new BackupStorageException(BackupStorageException.Reason.NOT_FOUND, "no such object: " + key));
      }
      return (new ByteArrayInputStream(entry.bytes()));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public synchronized Optional<StoredObjectInfo> head(String key)
   {
      Entry entry = objects.get(key);
      return (entry == null ? Optional.empty() : Optional.of(info(key, entry)));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public synchronized List<StoredObjectInfo> list(String prefix)
   {
      List<StoredObjectInfo> rs = new ArrayList<>();
      for(Map.Entry<String, Entry> e : objects.tailMap(prefix == null ? "" : prefix).entrySet())
      {
         if(prefix != null && !e.getKey().startsWith(prefix))
         {
            break;
         }
         rs.add(info(e.getKey(), e.getValue()));
      }
      return (rs);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public synchronized void copy(String sourceKey, String destinationKey)
   {
      Entry entry = objects.get(sourceKey);
      if(entry == null)
      {
         throw (new BackupStorageException(BackupStorageException.Reason.NOT_FOUND, "no such object: " + sourceKey));
      }
      objects.put(destinationKey, new Entry(entry.bytes(), entry.metadata(), clock.instant(), null));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public synchronized DeleteResult deletePermanent(String key)
   {
      Entry entry = objects.get(key);
      if(entry == null)
      {
         return (DeleteResult.NOT_FOUND);
      }
      if(entry.lockedUntil() != null && entry.lockedUntil().isAfter(clock.instant()))
      {
         return (DeleteResult.LOCKED);
      }
      objects.remove(key);
      return (DeleteResult.DELETED);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String presignGet(String key, Duration ttl, String downloadName)
   {
      return ("memory://" + key + "?ttl=" + ttl.toSeconds());
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public Set<StorageCapability> capabilities()
   {
      return (capabilities);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private StoredObjectInfo info(String key, Entry entry)
   {
      return (new StoredObjectInfo(key, entry.bytes().length, entry.lastModified(), entry.metadata(), entry.lockedUntil()));
   }
}
