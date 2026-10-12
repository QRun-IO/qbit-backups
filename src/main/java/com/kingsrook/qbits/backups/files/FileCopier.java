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


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.function.Function;
import com.kingsrook.qbits.backups.codec.ArtifactTransform;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qbits.backups.storage.WriteOptions;


/*******************************************************************************
 ** Makes sure each file that a record references is in the content-addressed
 ** store (spec 8.5), and says what it did.
 **
 ** For each reference: head the source (missing is reported, never silently
 ** skipped); find its SHA-256 (known from the reference provider, else the object
 ** is read once and hashed); if the store already has that hash the file is
 ** DEDUPLICATED and nothing is copied; otherwise it is copied (server-side when
 ** the source and the destination are the same storage and the plan does not
 ** encrypt, else streamed through the transform) and the hash observed while
 ** copying must equal the hash the key was derived from.  If the source changed
 ** in between, the file is hashed again and copied once more.
 *******************************************************************************/
public class FileCopier
{
   public static final String COPIED         = "COPIED";
   public static final String DEDUPLICATED   = "DEDUPLICATED";
   public static final String MISSING        = "MISSING";
   public static final String REFERENCE_ONLY = "REFERENCE_ONLY";

   private final Function<String, BackupStorage> sourceResolver;
   private final String                          destinationAlias;
   private final BackupStorage                   destination;
   private final String                          casPrefix;
   private final ArtifactTransform               transform;
   private final FileStoreRegistry               registry;
   private final boolean                         referenceOnly;
   private final String                          runKey;
   private String                                objectLockMode;
   private java.time.Instant                     objectLockUntil;



   /***************************************************************************
    ** The catalog side of the file store: which hashes are already stored.
    ***************************************************************************/
   public interface FileStoreRegistry
   {
      /***************************************************************************
       ** Whether the store knows this hash (and the object is expected to be there).
       ***************************************************************************/
      boolean isKnown(String sha256);


      /***************************************************************************
       ** Record a newly stored file.
       ***************************************************************************/
      void register(String sha256, long sizeBytes, String contentType, String casKey, boolean encrypted);
   }



   /***************************************************************************
    ** One line of the per-run file index.
    **
    ** @param tableName the referencing table
    ** @param recordKey the referencing record primary key
    ** @param field the referencing field
    ** @param sourceBucketAlias where the file was
    ** @param sourceKey the source key
    ** @param sha256 content hash (null when missing or reference-only without a known hash)
    ** @param size size in bytes (-1 when unknown)
    ** @param status COPIED, DEDUPLICATED, MISSING or REFERENCE_ONLY
    ** @param casKey the content-addressed key in the destination, if stored
    ***************************************************************************/
   public record IndexEntry(String tableName, String recordKey, String field, String sourceBucketAlias, String sourceKey,
                            String sha256, long size, String status, String casKey)
   {
   }



   /*******************************************************************************
    ** Constructor.
    **
    ** @param casPrefix prefix of the store, ending in a slash, e.g. qqq-backups/app/_files/sha256/
    *******************************************************************************/
   public FileCopier(Function<String, BackupStorage> sourceResolver, String destinationAlias, BackupStorage destination, String casPrefix,
                     ArtifactTransform transform, FileStoreRegistry registry, boolean referenceOnly, String runKey)
   {
      this.sourceResolver = sourceResolver;
      this.destinationAlias = destinationAlias;
      this.destination = destination;
      this.casPrefix = casPrefix;
      this.transform = transform == null ? ArtifactTransform.IDENTITY : transform;
      this.registry = registry;
      this.referenceOnly = referenceOnly;
      this.runKey = runKey;
   }



   /*******************************************************************************
    ** Lock every object this copier writes (a server-side copy cannot set a lock,
    ** so with a lock the bytes are streamed instead).
    *******************************************************************************/
   public FileCopier withObjectLock(String mode, java.time.Instant until)
   {
      this.objectLockMode = mode;
      this.objectLockUntil = until;
      return (this);
   }



   /*******************************************************************************
    ** The content-addressed key for a hash: prefix/ab/cd/sha256.
    *******************************************************************************/
   public static String casKey(String casPrefix, String sha256)
   {
      return (casPrefix + sha256.substring(0, 2) + "/" + sha256.substring(2, 4) + "/" + sha256);
   }



   /*******************************************************************************
    ** Process one reference.
    *******************************************************************************/
   public IndexEntry process(FileReference ref) throws IOException
   {
      BackupStorage source = sourceResolver.apply(ref.bucketAlias());
      Optional<StoredObjectInfo> head = source == null ? Optional.empty() : source.head(ref.key());
      if(head.isEmpty())
      {
         return (entry(ref, null, -1, MISSING, null));
      }
      long size = head.get().size();

      if(referenceOnly)
      {
         return (entry(ref, ref.knownSha256(), size, REFERENCE_ONLY, null));
      }

      String sha = ref.knownSha256() != null ? ref.knownSha256() : hashOf(source, ref.key());
      for(int attempt = 0; attempt < 2; attempt++)
      {
         String key = casKey(casPrefix, sha);
         if(registry.isKnown(sha) && destination.head(key).isPresent())
         {
            return (entry(ref, sha, size, DEDUPLICATED, key));
         }

         String observed = copy(source, ref, key);
         if(observed == null || observed.equals(sha))
         {
            registry.register(sha, size, ref.contentType(), key, transform.encrypts());
            return (entry(ref, sha, size, COPIED, key));
         }
         // the source changed while it was being copied: the object we wrote is under the wrong key
         destination.deletePermanent(key);
         sha = observed;
      }
      throw (new IOException("The file " + ref.key() + " kept changing while it was being copied"));
   }



   /*******************************************************************************
    ** Copy the file to the store; returns the hash observed while streaming, or null
    ** for a server-side copy (which cannot observe the bytes).
    *******************************************************************************/
   private String copy(BackupStorage source, FileReference ref, String key) throws IOException
   {
      if(source == destination && !transform.encrypts() && objectLockMode == null)
      {
         destination.copy(ref.key(), key);
         return (null);
      }

      WriteOptions options = WriteOptions.create().withContentType(ref.contentType() == null ? "application/octet-stream" : ref.contentType())
         .withMetadata("qqq-backup-run", runKey);
      if(objectLockMode != null)
      {
         options.withObjectLock(objectLockMode, objectLockUntil);
      }
      try(InputStream in = source.openRead(ref.key());
          HashingOutputStream plain = new HashingOutputStream(transform.wrap(destination.openWrite(key, options))))
      {
         in.transferTo(plain);
         plain.flush();
         plain.close();
         return (plain.getSha256());
      }
   }



   /*******************************************************************************
    ** Read an object once, to find its SHA-256.
    *******************************************************************************/
   private String hashOf(BackupStorage source, String key) throws IOException
   {
      try(InputStream in = source.openRead(key);
          HashingOutputStream sink = new HashingOutputStream(OutputStream.nullOutputStream()))
      {
         in.transferTo(sink);
         sink.close();
         return (sink.getSha256());
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private IndexEntry entry(FileReference ref, String sha, long size, String status, String casKey)
   {
      return (new IndexEntry(ref.tableName(), ref.recordKey(), ref.field(), ref.bucketAlias(), ref.key(), sha, size, status, casKey));
   }
}
