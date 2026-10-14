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

package com.kingsrook.qbits.backups.plan;


import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.prune.BackupPruner;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Test destination (spec 9): proves the plan destination works before the first
 ** night depends on it.  Writes a small probe object, reads it back, lists it, and
 ** deletes it through the prune alias (so the delete credential is exercised too).
 **
 ** On a bucket that is object-locked the probe is head only: a probe object could
 ** not be deleted again, so nothing is written; the prefix is listed, and the
 ** newest manifest is looked at with a head.  A capability report always comes with
 ** it.
 *******************************************************************************/
public final class DestinationProbe
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private DestinationProbe()
   {
   }



   /*******************************************************************************
    ** One thing that was checked.
    *******************************************************************************/
   public record Check(String name, boolean ok, String detail)
   {
   }



   /*******************************************************************************
    ** The whole report.
    *******************************************************************************/
   public record Report(boolean headOnly, List<Check> checks, List<String> capabilities, List<String> warnings)
   {
      /*******************************************************************************
       **
       *******************************************************************************/
      public boolean ok()
      {
         return (checks.stream().allMatch(Check::ok));
      }
   }



   /*******************************************************************************
    ** Probe the destination of a plan.
    *******************************************************************************/
   public static Report probe(QRecord plan)
   {
      return (probe(plan, false, null));
   }



   /*******************************************************************************
    ** Health monitoring only lists and heads; it never resolves prune credentials
    ** or probes capabilities. The runner bounds in-flight storage calls; check the
    ** deadline between calls because BackupStorage has no per-call timeout API.
    *******************************************************************************/
   public static Report probeReadOnly(QRecord plan, Instant deadline)
   {
      return (probe(plan, true, deadline));
   }



   /*******************************************************************************
    ** Share storage resolution and the existing locked-bucket read path.
    *******************************************************************************/
   private static Report probe(QRecord plan, boolean readOnly, Instant deadline)
   {
      List<Check>  checks   = new ArrayList<>();
      List<String> warnings = new ArrayList<>();

      String alias = plan.getValueString("storageBucketAlias");
      BackupStorage storage;
      try
      {
         requireTime(deadline);
         storage = BackupsRuntime.storageFor(alias);
         requireTime(deadline);
      }
      catch(RuntimeException e)
      {
         checks.add(new Check("Open the backup bucket " + alias, false, e.getMessage()));
         return (new Report(readOnly, checks, List.of(), warnings));
      }
      checks.add(new Check("Open the backup bucket " + alias, true, "found"));

      if(readOnly)
      {
         headOnly(plan, storage, BackupPruner.planPrefix(plan), checks, deadline);
         return (new Report(true, checks, List.of(), warnings));
      }

      BackupStorage deleteSide = storage;
      String        pruneAlias = plan.getValueString("pruneBucketAlias");
      if(pruneAlias == null || pruneAlias.isBlank())
      {
         warnings.add("No prune bucket is set, so one key both writes and deletes backups.");
      }
      else
      {
         try
         {
            deleteSide = BackupsRuntime.storageFor(pruneAlias);
            checks.add(new Check("Open the prune bucket " + pruneAlias, true, "found"));
         }
         catch(RuntimeException e)
         {
            checks.add(new Check("Open the prune bucket " + pruneAlias, false, e.getMessage()));
         }
      }

      List<String> capabilities = new ArrayList<>();
      for(BackupStorage.StorageCapability capability : BackupStorage.StorageCapability.values())
      {
         capabilities.add(capability.name() + ": " + (storage.capabilities().contains(capability) ? "supported" : "not supported"));
      }

      String lockMode = plan.getValueString("objectLockMode");
      boolean locked  = lockMode != null && !"NONE".equals(lockMode);
      if(locked && !storage.capabilities().contains(BackupStorage.StorageCapability.OBJECT_LOCK))
      {
         checks.add(new Check("Object lock " + lockMode, false, "the plan asks for object lock, but this bucket does not support it"));
      }

      String prefix = BackupPruner.planPrefix(plan);
      if(locked)
      {
         headOnly(plan, storage, prefix, checks, null);
      }
      else
      {
         writeReadListDelete(storage, deleteSide, prefix + ".qqq-probe/", checks);
      }
      return (new Report(locked, checks, capabilities, warnings));
   }



   /*******************************************************************************
    ** The probe for a locked bucket: list the prefix, and head the newest manifest.
    *******************************************************************************/
   private static void headOnly(QRecord plan, BackupStorage storage, String prefix, List<Check> checks, Instant deadline)
   {
      checks.add(new Check("Write and delete a probe object", true,
         deadline == null ? "skipped: object lock is on, a probe object could not be deleted again" : "skipped: read-only probe"));
      checks.add(attempt("List the plan prefix", deadline, () ->
      {
         List<StoredObjectInfo> objects = storage.list(prefix);
         return (objects.size() + " objects under " + prefix);
      }));

      Integer lastSuccess = plan.getValueInteger("lastSuccessRunId");
      if(lastSuccess == null)
      {
         checks.add(new Check("Head the newest manifest", true, "there is no successful run yet"));
         return;
      }
      checks.add(attempt("Head the newest manifest", deadline, () ->
      {
         QRecord run = new com.kingsrook.qbits.backups.run.BackupCatalog().get(com.kingsrook.qbits.backups.model.BackupsTables.RUN, lastSuccess).orElseThrow();
         String  key = run.getValueString("manifestKey");
         requireTime(deadline);
         if(key == null || storage.head(key).isEmpty())
         {
            throw (new IllegalStateException("the manifest of the newest successful run is not in the bucket"));
         }
         return ("found " + key);
      }));
   }



   /*******************************************************************************
    ** Write, read back, list and delete a probe object.
    *******************************************************************************/
   private static void writeReadListDelete(BackupStorage storage, BackupStorage deleteSide, String probePrefix, List<Check> checks)
   {
      String key     = probePrefix + UUID.randomUUID();
      byte[] content = ("qqq backups probe " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

      Check write = attempt("Write a probe object", () ->
      {
         try(OutputStream out = storage.openWrite(key, WriteOptions.create().withContentType("text/plain")))
         {
            out.write(content);
         }
         return ("wrote " + content.length + " bytes");
      });
      checks.add(write);
      if(!write.ok())
      {
         return;
      }

      checks.add(attempt("Read it back", () ->
      {
         try(InputStream in = storage.openRead(key))
         {
            if(!java.util.Arrays.equals(content, in.readAllBytes()))
            {
               throw (new IllegalStateException("the bytes read back are not the bytes written"));
            }
         }
         return ("the same bytes came back");
      }));

      checks.add(attempt("List it", () ->
      {
         if(storage.list(probePrefix).stream().noneMatch(o -> o.key().equals(key)))
         {
            throw (new IllegalStateException("the probe object is not in the listing"));
         }
         return ("found in the listing");
      }));

      checks.add(attempt("Delete it", () ->
      {
         BackupStorage.DeleteResult result = deleteSide.deletePermanent(key);
         if(result != BackupStorage.DeleteResult.DELETED)
         {
            throw (new IllegalStateException("the delete returned " + result));
         }
         return ("deleted");
      }));
   }



   /***************************************************************************
    **
    ***************************************************************************/
   @FunctionalInterface
   private interface Attempt
   {
      /*******************************************************************************
       ** Execute one diagnostic operation.
       *******************************************************************************/
      String run() throws Exception;
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static Check attempt(String name, Attempt attempt)
   {
      return (attempt(name, null, attempt));
   }



   /*******************************************************************************
    ** Never begin another storage operation after the health budget expires.
    *******************************************************************************/
   private static Check attempt(String name, Instant deadline, Attempt attempt)
   {
      try
      {
         requireTime(deadline);
         String detail = attempt.run();
         requireTime(deadline);
         return (new Check(name, true, detail));
      }
      catch(Exception e)
      {
         return (new Check(name, false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      }
   }



   /*******************************************************************************
    ** Existing on-demand diagnostics have no deadline; health probes always do.
    *******************************************************************************/
   private static void requireTime(Instant deadline)
   {
      if(deadline != null && (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)))
      {
         throw (new IllegalStateException("Destination probe timed out"));
      }
   }
}
