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

package com.kingsrook.qbits.backups.codec;


import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Streams the rows of one table into numbered parts in storage.  Each row goes
 ** through the row codec, then (per part) into a hash of the raw bytes, gzip,
 ** the artifact transform (encryption) and a hash of the stored bytes, and out
 ** through BackupStorage.openWrite.  Memory is O(row); nothing touches a temp disk.
 **
 ** Whatever the output format, a canonical JSONL hash of every row (in the order
 ** written, which is primary key order) is kept: that is the rowsHash a verifier
 ** recomputes (spec 8.2), independent of compression, encryption and part sizes.
 *******************************************************************************/
public class TableArtifactWriter implements Closeable
{
   private final BackupStorage     storage;
   private final String            keyPrefix;
   private final QTableMetaData    table;
   private final RowCodec          codec;
   private final JsonlRowCodec     canonical;
   private final boolean           csv;
   private final boolean           gzip;
   private final long              targetPartBytes;
   private final long              maxRowsPerPart;
   private final ArtifactTransform transform;
   private final String            runKey;

   private final MessageDigest rowsDigest = HashingOutputStream.newSha256();
   private final List<PartResult> parts   = new ArrayList<>();

   private int                 partNumber = 0;
   private PartState           current;
   private long                totalRows  = 0;
   private String              firstKeyOfTable;
   private String              lastKeyOfTable;

   private Consumer<PartResult> partListener;
   private String               objectLockMode;
   private Instant              objectLockUntil;



   /***************************************************************************
    ** The outcome of one written part.
    ***************************************************************************/
   public record PartResult(int partNumber, String key, long rows, long bytesRaw, long bytesStored, String sha256Raw, String sha256Stored,
                            String firstKey, String lastKey, boolean encrypted, String format, String compression)
   {
   }



   /***************************************************************************
    **
    ***************************************************************************/
   private static class PartState
   {
      String               key;
      HashingOutputStream  raw;
      HashingOutputStream  stored;
      long                 rows;
      String               firstKey;
      String               lastKey;
   }



   /*******************************************************************************
    ** Constructor.
    **
    ** @param keyPrefix the key prefix of the table directory, ending in a slash
    ** @param format JSONL or CSV
    ** @param compression GZIP or NONE
    *******************************************************************************/
   public TableArtifactWriter(BackupStorage storage, String keyPrefix, QTableMetaData table, Collection<String> excludedFields, String format,
                              String compression, long targetPartBytes, long maxRowsPerPart, ArtifactTransform transform, String runKey)
   {
      this.storage = storage;
      this.keyPrefix = keyPrefix;
      this.table = table;
      this.csv = "CSV".equals(format);
      this.canonical = new JsonlRowCodec(table, excludedFields);
      this.codec = csv ? new CsvRowCodec(table, excludedFields) : canonical;
      this.gzip = "GZIP".equals(compression);
      this.targetPartBytes = targetPartBytes;
      this.maxRowsPerPart = maxRowsPerPart;
      this.transform = transform == null ? ArtifactTransform.IDENTITY : transform;
      this.runKey = runKey;

      if(!gzip && !"NONE".equals(compression))
      {
         throw (new IllegalArgumentException("Unsupported compression: " + compression));
      }
   }



   /*******************************************************************************
    ** Be told about each part as it is committed to storage (so the catalog records
    ** a part the moment it is complete, which is what a resume starts from).
    *******************************************************************************/
   public TableArtifactWriter withPartListener(Consumer<PartResult> partListener)
   {
      this.partListener = partListener;
      return (this);
   }



   /*******************************************************************************
    ** Apply an object lock to every part written.
    *******************************************************************************/
   public TableArtifactWriter withObjectLock(String mode, Instant until)
   {
      this.objectLockMode = mode;
      this.objectLockUntil = until;
      return (this);
   }



   /*******************************************************************************
    ** Continue after parts that were already written (resume): numbering carries on
    ** after them and the table totals include them.  The caller then feeds the rows
    ** hash with seedRowsHash.
    *******************************************************************************/
   public void resumeFrom(List<PartResult> priorParts)
   {
      if(priorParts.isEmpty())
      {
         return;
      }
      parts.addAll(priorParts);
      partNumber = priorParts.get(priorParts.size() - 1).partNumber();
      for(PartResult part : priorParts)
      {
         totalRows += part.rows();
      }
      firstKeyOfTable = priorParts.get(0).firstKey();
      lastKeyOfTable = priorParts.get(priorParts.size() - 1).lastKey();
   }



   /*******************************************************************************
    ** Feed already-written canonical JSONL bytes into the rows hash (resume).
    *******************************************************************************/
   public void seedRowsHash(byte[] bytes, int offset, int length)
   {
      rowsDigest.update(bytes, offset, length);
   }



   /*******************************************************************************
    ** Write one record.
    *******************************************************************************/
   public void write(QRecord record) throws IOException
   {
      if(current == null)
      {
         openPart();
      }

      String       pk   = String.valueOf(record.getValue(table.getPrimaryKeyField()));
      byte[]       line = (codec.encode(record) + "\n").getBytes(StandardCharsets.UTF_8);
      current.raw.write(line);
      if(current.firstKey == null)
      {
         current.firstKey = pk;
      }
      current.lastKey = pk;
      current.rows++;
      totalRows++;
      if(firstKeyOfTable == null)
      {
         firstKeyOfTable = pk;
      }
      lastKeyOfTable = pk;

      rowsDigest.update((canonical.encode(record) + "\n").getBytes(StandardCharsets.UTF_8));

      if(current.raw.getCount() >= targetPartBytes || current.rows >= maxRowsPerPart)
      {
         closePart();
      }
   }



   /*******************************************************************************
    ** Finish: close the last part (writing an empty part if there were no rows at all).
    *******************************************************************************/
   public List<PartResult> finish() throws IOException
   {
      if(current == null && parts.isEmpty())
      {
         openPart();
      }
      if(current != null)
      {
         closePart();
      }
      return (parts);
   }



   /*******************************************************************************
    ** The canonical content hash of all rows written, as sha256:hex.  Valid after finish().
    *******************************************************************************/
   public String rowsHash()
   {
      return ("sha256:" + HashingOutputStream.hex(rowsDigest.digest()));
   }



   /*******************************************************************************
    ** Total rows written.
    *******************************************************************************/
   public long getTotalRows()
   {
      return (totalRows);
   }



   /*******************************************************************************
    ** First primary key written.
    *******************************************************************************/
   public String getFirstKey()
   {
      return (firstKeyOfTable);
   }



   /*******************************************************************************
    ** Last primary key written.
    *******************************************************************************/
   public String getLastKey()
   {
      return (lastKeyOfTable);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void close() throws IOException
   {
      if(current != null)
      {
         // an abandoned part is never committed to storage
         current.raw.close();
         current = null;
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void openPart() throws IOException
   {
      partNumber++;
      current = new PartState();
      String extension = csv ? "csv" : "jsonl";
      current.key = keyPrefix + String.format("part-%05d.%s%s%s", partNumber, extension, gzip ? ".gz" : "", transform.keySuffix());

      WriteOptions options = WriteOptions.create().withContentType("application/octet-stream")
         .withMetadata("qqq-backup-run", runKey)
         .withMetadata("qqq-backup-table", table.getName());
      if(objectLockMode != null)
      {
         options.withObjectLock(objectLockMode, objectLockUntil);
      }
      OutputStream toStorage = storage.openWrite(current.key, options);
      current.stored = new HashingOutputStream(toStorage);
      OutputStream transformed = transform.wrap(current.stored);
      OutputStream compressed  = gzip ? new GZIPOutputStream(transformed) : transformed;
      current.raw = new HashingOutputStream(compressed);

      String header = codec.header();
      if(header != null)
      {
         current.raw.write((header + "\n").getBytes(StandardCharsets.UTF_8));
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private void closePart() throws IOException
   {
      current.raw.close();
      PartResult result = new PartResult(partNumber, current.key, current.rows, current.raw.getCount(), current.stored.getCount(),
         current.raw.getSha256(), current.stored.getSha256(), current.firstKey, current.lastKey, transform.encrypts(),
         csv ? "CSV" : "JSONL", gzip ? "GZIP" : "NONE");
      parts.add(result);
      current = null;
      if(partListener != null)
      {
         partListener.accept(result);
      }
   }
}
