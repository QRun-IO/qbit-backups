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

package com.kingsrook.qbits.backups.verify;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;
import com.kingsrook.qbits.backups.codec.HashingOutputStream;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.jobs.plans.JobChunkScope;


/*******************************************************************************
 ** Reads one stored part end to end the way a restore would: from storage,
 ** through the decrypt hook, through gunzip, and hands each line to a consumer,
 ** while hashing the stored bytes and the decoded bytes.
 *******************************************************************************/
final class PartScanner
{
   /***************************************************************************
    ** What a scan saw.
    **
    ** @param lines the number of lines handed to the consumer (0 when none was given)
    ** @param sha256Stored SHA-256 of the bytes in the bucket
    ** @param sha256Raw SHA-256 of the bytes after decrypting and decompressing
    ***************************************************************************/
   record Scan(long lines, String sha256Stored, String sha256Raw)
   {
   }



   /***************************************************************************
    ** Receives each line of a part (no trailing newline).
    ***************************************************************************/
   @FunctionalInterface
   interface LineConsumer
   {
      /***************************************************************************
       ** One line.
       ***************************************************************************/
      void accept(String line) throws QException;
   }



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private PartScanner()
   {
   }



   /*******************************************************************************
    ** Scan a part.  With no consumer the part is read to its end and hashed only (a
    ** CSV part, where lines are not rows).
    *******************************************************************************/
   static Scan scan(RunBundle bundle, BackupManifest.Part part, LineConsumer consumer) throws IOException, QException
   {
      JobChunkScope.checkBetweenPages();
      MessageDigest storedDigest = HashingOutputStream.newSha256();
      MessageDigest rawDigest    = HashingOutputStream.newSha256();
      long          lines        = 0;

      try(InputStream fromStorage = bundle.storage().openRead(bundle.runPrefix() + part.key());
          DigestInputStream stored = new DigestInputStream(fromStorage, storedDigest))
      {
         InputStream plain        = part.encrypted() ? bundle.transform().unwrap(stored) : stored;
         InputStream decompressed = "GZIP".equals(part.compression()) ? new GZIPInputStream(plain) : plain;

         try(DigestInputStream raw = new DigestInputStream(decompressed, rawDigest))
         {
            if(consumer == null)
            {
               raw.transferTo(OutputStream.nullOutputStream());
            }
            else
            {
               BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
               String         line;
               while((line = reader.readLine()) != null)
               {
                  if(lines % 1000 == 0)
                  {
                     JobChunkScope.checkBetweenPages();
                  }
                  lines++;
                  consumer.accept(line);
               }
            }
            // anything after the decoded stream (a gzip trailer, padding) still counts toward the stored hash
            stored.transferTo(OutputStream.nullOutputStream());
         }
      }

      return (new Scan(lines, HashingOutputStream.hex(storedDigest.digest()), HashingOutputStream.hex(rawDigest.digest())));
   }
}
