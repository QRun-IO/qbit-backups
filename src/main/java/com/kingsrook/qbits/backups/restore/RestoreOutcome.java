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

package com.kingsrook.qbits.backups.restore;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/*******************************************************************************
 ** What a restore pass found (Review) or did (Execute): the per-table counts, the
 ** schema comparison, the sample diffs and the file counts.  Review and Execute
 ** share one algorithm, so the Review numbers are the numbers the restore produces.
 ** Nothing here ever holds a sensitive value: sample diffs are masked when built.
 *******************************************************************************/
public class RestoreOutcome
{
   private final Map<String, TableOutcome> tables   = new LinkedHashMap<>();
   private final List<String>              warnings = new ArrayList<>();
   private final List<String>              errors   = new ArrayList<>();
   private final List<String>              notes    = new ArrayList<>();

   private Integer preRestoreRunId;
   private Integer restoreRunId;
   private int     sequencesResynced;
   private int     filesFailed;



   /*******************************************************************************
    ** One changed record, as masked "field: old -> new" texts.
    *******************************************************************************/
   public record Sample(String key, List<String> changes)
   {
   }



   /*******************************************************************************
    ** The result for one table.
    *******************************************************************************/
   public static class TableOutcome
   {
      private final String name;
      private final String targetName;

      private String       status           = "PENDING";
      private String       schemaStatus     = "MATCH";
      private final List<String> schemaNotes = new ArrayList<>();
      private final List<String> unmappedFields = new ArrayList<>();
      private final List<String> unmappedReferences = new ArrayList<>();
      private final List<String> problems   = new ArrayList<>();
      private final List<Sample> samples    = new ArrayList<>();
      private long         rows;
      private long         inserted;
      private long         updated;
      private long         unchanged;
      private long         skipped;
      private long         deleted;
      private long         conflicts;
      private long         orphans;
      private long         errored;
      private boolean      sequenceNeeded;
      private boolean      sequenceResynced;
      private long         updatedSampleTotal;
      private int          filesToRestore;
      private int          filesPresent;
      private int          filesConflicting;
      private int          filesMissing;
      private int          filesRestored;
      private long         fileBytesRestored;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public TableOutcome(String name, String targetName)
      {
         this.name = name;
         this.targetName = targetName;
      }



      /*******************************************************************************
       ** Getter for name (the table in the backup)
       *******************************************************************************/
      public String getName()
      {
         return (name);
      }



      /*******************************************************************************
       ** Getter for targetName (the table written)
       *******************************************************************************/
      public String getTargetName()
      {
         return (targetName);
      }



      /*******************************************************************************
       ** Getter for status
       *******************************************************************************/
      public String getStatus()
      {
         return (status);
      }



      /*******************************************************************************
       ** Setter for status
       *******************************************************************************/
      public void setStatus(String status)
      {
         this.status = status;
      }



      /*******************************************************************************
       ** Getter for schemaStatus
       *******************************************************************************/
      public String getSchemaStatus()
      {
         return (schemaStatus);
      }



      /*******************************************************************************
       ** Setter for schemaStatus
       *******************************************************************************/
      public void setSchemaStatus(String schemaStatus)
      {
         this.schemaStatus = schemaStatus;
      }



      /*******************************************************************************
       ** Getter for schemaNotes
       *******************************************************************************/
      public List<String> getSchemaNotes()
      {
         return (schemaNotes);
      }



      /*******************************************************************************
       ** Getter for unmappedFields (in the backup, not in the target)
       *******************************************************************************/
      public List<String> getUnmappedFields()
      {
         return (unmappedFields);
      }



      /*******************************************************************************
       ** Getter for unmappedReferences (id fields that metadata cannot trace)
       *******************************************************************************/
      public List<String> getUnmappedReferences()
      {
         return (unmappedReferences);
      }



      /*******************************************************************************
       ** Getter for problems (error messages, never row values)
       *******************************************************************************/
      public List<String> getProblems()
      {
         return (problems);
      }



      /*******************************************************************************
       ** Getter for samples
       *******************************************************************************/
      public List<Sample> getSamples()
      {
         return (samples);
      }



      /*******************************************************************************
       ** Getter for rows
       *******************************************************************************/
      public long getRows()
      {
         return (rows);
      }



      /*******************************************************************************
       ** Add rows read from the backup.
       *******************************************************************************/
      public void addRows(long n)
      {
         rows += n;
      }



      /*******************************************************************************
       ** Getter for inserted
       *******************************************************************************/
      public long getInserted()
      {
         return (inserted);
      }



      /*******************************************************************************
       ** Add inserted
       *******************************************************************************/
      public void addInserted(long n)
      {
         inserted += n;
      }



      /*******************************************************************************
       ** Getter for updated
       *******************************************************************************/
      public long getUpdated()
      {
         return (updated);
      }



      /*******************************************************************************
       ** Add updated
       *******************************************************************************/
      public void addUpdated(long n)
      {
         updated += n;
      }



      /*******************************************************************************
       ** Getter for unchanged
       *******************************************************************************/
      public long getUnchanged()
      {
         return (unchanged);
      }



      /*******************************************************************************
       ** Add unchanged
       *******************************************************************************/
      public void addUnchanged(long n)
      {
         unchanged += n;
      }



      /*******************************************************************************
       ** Getter for skipped (INSERT_NEW rows that exist with different content)
       *******************************************************************************/
      public long getSkipped()
      {
         return (skipped);
      }



      /*******************************************************************************
       ** Add skipped
       *******************************************************************************/
      public void addSkipped(long n)
      {
         skipped += n;
      }



      /*******************************************************************************
       ** Getter for deleted
       *******************************************************************************/
      public long getDeleted()
      {
         return (deleted);
      }



      /*******************************************************************************
       ** Add deleted
       *******************************************************************************/
      public void addDeleted(long n)
      {
         deleted += n;
      }



      /*******************************************************************************
       ** Getter for conflicts (rows that collide on a unique key)
       *******************************************************************************/
      public long getConflicts()
      {
         return (conflicts);
      }



      /*******************************************************************************
       ** Add conflicts
       *******************************************************************************/
      public void addConflicts(long n)
      {
         conflicts += n;
      }



      /*******************************************************************************
       ** Getter for orphans (rows whose parent is in neither the backup nor the target)
       *******************************************************************************/
      public long getOrphans()
      {
         return (orphans);
      }



      /*******************************************************************************
       ** Add orphans
       *******************************************************************************/
      public void addOrphans(long n)
      {
         orphans += n;
      }



      /*******************************************************************************
       ** Getter for errored
       *******************************************************************************/
      public long getErrored()
      {
         return (errored);
      }



      /*******************************************************************************
       ** Add errored
       *******************************************************************************/
      public void addErrored(long n)
      {
         errored += n;
      }



      /*******************************************************************************
       ** Getter for sequenceNeeded
       *******************************************************************************/
      public boolean getSequenceNeeded()
      {
         return (sequenceNeeded);
      }



      /*******************************************************************************
       ** Setter for sequenceNeeded
       *******************************************************************************/
      public void setSequenceNeeded(boolean sequenceNeeded)
      {
         this.sequenceNeeded = sequenceNeeded;
      }



      /*******************************************************************************
       ** Getter for sequenceResynced
       *******************************************************************************/
      public boolean getSequenceResynced()
      {
         return (sequenceResynced);
      }



      /*******************************************************************************
       ** Setter for sequenceResynced
       *******************************************************************************/
      public void setSequenceResynced(boolean sequenceResynced)
      {
         this.sequenceResynced = sequenceResynced;
      }



      /*******************************************************************************
       ** Getter for the number of updated records seen (the sample list is capped)
       *******************************************************************************/
      public long getUpdatedSampleTotal()
      {
         return (updatedSampleTotal);
      }



      /*******************************************************************************
       ** Count one more changed record (sampled or not).
       *******************************************************************************/
      public void countChangedRecord()
      {
         updatedSampleTotal++;
      }



      /*******************************************************************************
       ** Getter for filesToRestore
       *******************************************************************************/
      public int getFilesToRestore()
      {
         return (filesToRestore);
      }



      /*******************************************************************************
       ** Add filesToRestore
       *******************************************************************************/
      public void addFilesToRestore(int n)
      {
         filesToRestore += n;
      }



      /*******************************************************************************
       ** Getter for filesPresent
       *******************************************************************************/
      public int getFilesPresent()
      {
         return (filesPresent);
      }



      /*******************************************************************************
       ** Add filesPresent
       *******************************************************************************/
      public void addFilesPresent(int n)
      {
         filesPresent += n;
      }



      /*******************************************************************************
       ** Getter for filesConflicting
       *******************************************************************************/
      public int getFilesConflicting()
      {
         return (filesConflicting);
      }



      /*******************************************************************************
       ** Add filesConflicting
       *******************************************************************************/
      public void addFilesConflicting(int n)
      {
         filesConflicting += n;
      }



      /*******************************************************************************
       ** Getter for filesMissing (missing from the backup)
       *******************************************************************************/
      public int getFilesMissing()
      {
         return (filesMissing);
      }



      /*******************************************************************************
       ** Add filesMissing
       *******************************************************************************/
      public void addFilesMissing(int n)
      {
         filesMissing += n;
      }



      /*******************************************************************************
       ** Getter for filesRestored
       *******************************************************************************/
      public int getFilesRestored()
      {
         return (filesRestored);
      }



      /*******************************************************************************
       ** Add filesRestored
       *******************************************************************************/
      public void addFilesRestored(int n)
      {
         filesRestored += n;
      }



      /*******************************************************************************
       ** Getter for fileBytesRestored
       *******************************************************************************/
      public long getFileBytesRestored()
      {
         return (fileBytesRestored);
      }



      /*******************************************************************************
       ** Add fileBytesRestored
       *******************************************************************************/
      public void addFileBytesRestored(long n)
      {
         fileBytesRestored += n;
      }
   }



   /*******************************************************************************
    ** The outcome of one table, created on first use.
    *******************************************************************************/
   public TableOutcome table(String name, String targetName)
   {
      return (tables.computeIfAbsent(name, k -> new TableOutcome(name, targetName)));
   }



   /*******************************************************************************
    ** The tables, in restore order.
    *******************************************************************************/
   public Map<String, TableOutcome> getTables()
   {
      return (tables);
   }



   /*******************************************************************************
    ** Run-level warnings.
    *******************************************************************************/
   public List<String> getWarnings()
   {
      return (warnings);
   }



   /*******************************************************************************
    ** Run-level errors (the restore does not run when there are any).
    *******************************************************************************/
   public List<String> getErrors()
   {
      return (errors);
   }



   /*******************************************************************************
    ** Run-level informational notes.
    *******************************************************************************/
   public List<String> getNotes()
   {
      return (notes);
   }



   /*******************************************************************************
    ** Getter for preRestoreRunId
    *******************************************************************************/
   public Integer getPreRestoreRunId()
   {
      return (preRestoreRunId);
   }



   /*******************************************************************************
    ** Setter for preRestoreRunId
    *******************************************************************************/
   public void setPreRestoreRunId(Integer preRestoreRunId)
   {
      this.preRestoreRunId = preRestoreRunId;
   }



   /*******************************************************************************
    ** Getter for restoreRunId
    *******************************************************************************/
   public Integer getRestoreRunId()
   {
      return (restoreRunId);
   }



   /*******************************************************************************
    ** Setter for restoreRunId
    *******************************************************************************/
   public void setRestoreRunId(Integer restoreRunId)
   {
      this.restoreRunId = restoreRunId;
   }



   /*******************************************************************************
    ** Getter for sequencesResynced
    *******************************************************************************/
   public int getSequencesResynced()
   {
      return (sequencesResynced);
   }



   /*******************************************************************************
    ** Setter for sequencesResynced
    *******************************************************************************/
   public void setSequencesResynced(int sequencesResynced)
   {
      this.sequencesResynced = sequencesResynced;
   }



   /*******************************************************************************
    ** Getter for filesFailed
    *******************************************************************************/
   public int getFilesFailed()
   {
      return (filesFailed);
   }



   /*******************************************************************************
    ** Add filesFailed
    *******************************************************************************/
   public void addFilesFailed(int n)
   {
      filesFailed += n;
   }



   /*******************************************************************************
    ** A total over the tables.
    *******************************************************************************/
   public long sum(java.util.function.ToLongFunction<TableOutcome> getter)
   {
      return (tables.values().stream().mapToLong(getter).sum());
   }
}
