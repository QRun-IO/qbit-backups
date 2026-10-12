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
import java.util.List;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLine;
import com.kingsrook.qqq.backend.core.model.actions.processes.ProcessSummaryLineInterface;
import com.kingsrook.qqq.backend.core.model.actions.processes.Status;


/*******************************************************************************
 ** Turns a RestoreOutcome into the standard Review and Result summary lines
 ** (spec 9.3).  One group of lines per table, in restore order.  Every line has
 ** its future wording (Review) and its past wording (Result); sample diffs are
 ** bullets on one line per table, with sensitive values already masked.
 *******************************************************************************/
public final class RestoreSummaries
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private RestoreSummaries()
   {
   }



   /*******************************************************************************
    ** A line with all four wordings.
    *******************************************************************************/
   public static ProcessSummaryLine line(Status status, long count, String singularFuture, String pluralFuture, String singularPast, String pluralPast)
   {
      ProcessSummaryLine line = new ProcessSummaryLine(status, (int) Math.min(Integer.MAX_VALUE, count), null);
      line.setSingularFutureMessage(singularFuture);
      line.setPluralFutureMessage(pluralFuture);
      line.setSingularPastMessage(singularPast);
      line.setPluralPastMessage(pluralPast);
      return (line);
   }



   /*******************************************************************************
    ** A one-off message line (the same text before and after).
    *******************************************************************************/
   public static ProcessSummaryLine message(Status status, String text)
   {
      return (line(status, 1, text, text, text, text));
   }



   /*******************************************************************************
    ** The lines of an outcome.
    *******************************************************************************/
   public static List<ProcessSummaryLineInterface> lines(RestoreOutcome outcome, boolean isResult)
   {
      List<ProcessSummaryLineInterface> lines = new ArrayList<>();

      for(String error : outcome.getErrors())
      {
         lines.add(message(Status.ERROR, error));
      }

      for(RestoreOutcome.TableOutcome t : outcome.getTables().values())
      {
         tableLines(lines, t, isResult);
      }

      long sequences = outcome.sum(t -> t.getSequenceNeeded() ? 1 : 0);
      if(sequences > 0 && !isResult)
      {
         lines.add(line(Status.INFO, sequences, "table will have its key sequence resynced", "tables will have their key sequences resynced",
            "table had its key sequence resynced", "tables had their key sequences resynced"));
      }
      if(isResult && outcome.getSequencesResynced() > 0)
      {
         lines.add(line(Status.OK, outcome.getSequencesResynced(), "key sequence was resynced", "key sequences were resynced",
            "key sequence was resynced", "key sequences were resynced"));
      }
      if(isResult && outcome.getPreRestoreRunId() != null)
      {
         lines.add(message(Status.INFO, "A backup of the target tables was taken first (backup run " + outcome.getPreRestoreRunId() + "), so this restore can be undone."));
      }
      if(isResult && outcome.getFilesFailed() > 0)
      {
         lines.add(line(Status.ERROR, outcome.getFilesFailed(), "file could not be restored", "files could not be restored", "file could not be restored", "files could not be restored"));
      }

      for(String warning : outcome.getWarnings())
      {
         lines.add(message(Status.WARNING, warning));
      }
      for(String note : outcome.getNotes())
      {
         lines.add(message(Status.INFO, note));
      }
      return (lines);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void tableLines(List<ProcessSummaryLineInterface> lines, RestoreOutcome.TableOutcome t, boolean isResult)
   {
      String in = " " + t.getTargetName();

      if("SKIPPED".equals(t.getStatus()))
      {
         lines.add(message(Status.ERROR, "The table " + t.getName() + " will not be restored: its schema does not fit the backup"));
      }
      for(String note : t.getSchemaNotes())
      {
         lines.add(message("MISMATCH".equals(t.getSchemaStatus()) ? Status.ERROR : Status.WARNING, "Schema of " + t.getName() + ": " + note));
      }
      for(String reference : t.getUnmappedReferences())
      {
         lines.add(message(Status.WARNING, "The field " + reference + " looks like a reference but cannot be traced, so it is not rewritten to the new ids"));
      }

      add(lines, line(Status.OK, t.getInserted(), "record will be inserted into" + in, "records will be inserted into" + in,
         "record was inserted into" + in, "records were inserted into" + in));
      add(lines, line(Status.OK, t.getUpdated(), "record will be updated in" + in, "records will be updated in" + in,
         "record was updated in" + in, "records were updated in" + in));
      add(lines, line(Status.INFO, t.getUnchanged(), "record is unchanged in" + in, "records are unchanged in" + in,
         "record was already the same in" + in, "records were already the same in" + in));
      add(lines, line(Status.INFO, t.getSkipped(), "record exists with different content and will be left alone in" + in,
         "records exist with different content and will be left alone in" + in,
         "record existed with different content and was left alone in" + in, "records existed with different content and were left alone in" + in));
      add(lines, line(Status.WARNING, t.getDeleted(), "record will be deleted from" + in + " (REPLACE)", "records will be deleted from" + in + " (REPLACE)",
         "record was deleted from" + in + " (REPLACE)", "records were deleted from" + in + " (REPLACE)"));
      add(lines, line(Status.WARNING, t.getConflicts(), "record conflicts on a unique key in" + in + " and will be skipped",
         "records conflict on a unique key in" + in + " and will be skipped",
         "record conflicted on a unique key in" + in + " and was skipped", "records conflicted on a unique key in" + in + " and were skipped"));
      add(lines, line(Status.WARNING, t.getOrphans(), "record in" + in + " references a parent that is missing from the backup and the target",
         "records in" + in + " reference a parent that is missing from the backup and the target",
         "record in" + in + " referenced a parent that is missing from the backup and the target",
         "records in" + in + " referenced a parent that is missing from the backup and the target"));
      if(isResult)
      {
         add(lines, line(Status.ERROR, t.getErrored(), "record could not be written to" + in, "records could not be written to" + in,
            "record could not be written to" + in, "records could not be written to" + in));
         for(String problem : t.getProblems())
         {
            lines.add(message(Status.ERROR, problem));
         }
      }

      fileLines(lines, t, isResult);

      if(!t.getSamples().isEmpty() && !isResult)
      {
         ProcessSummaryLine sample = line(Status.INFO, t.getSamples().size(),
            "changed record in" + in + " (field: now -> from the backup; sensitive values are hidden)",
            "changed records in" + in + " (field: now -> from the backup; sensitive values are hidden)", "", "");
         ArrayList<String> bullets = new ArrayList<>();
         for(RestoreOutcome.Sample s : t.getSamples())
         {
            bullets.add("#" + s.key() + "  " + String.join("; ", s.changes()));
         }
         sample.setBulletsOfText(bullets);
         lines.add(sample);
      }
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void fileLines(List<ProcessSummaryLineInterface> lines, RestoreOutcome.TableOutcome t, boolean isResult)
   {
      String in = " for" + " " + t.getTargetName();
      if(isResult)
      {
         add(lines, line(Status.OK, t.getFilesRestored(), "file was restored" + in, "files were restored" + in, "file was restored" + in, "files were restored" + in));
      }
      else
      {
         add(lines, line(Status.OK, t.getFilesToRestore(), "file will be restored" + in, "files will be restored" + in, "file was restored" + in, "files were restored" + in));
      }
      add(lines, line(Status.INFO, t.getFilesPresent(), "file is already there with the same checksum" + in + " and is skipped",
         "files are already there with the same checksum" + in + " and are skipped",
         "file was already there with the same checksum" + in, "files were already there with the same checksum" + in));
      add(lines, line(Status.WARNING, t.getFilesConflicting(), "file exists with different content" + in + " (kept, unless the mode is REPLACE)",
         "files exist with different content" + in + " (kept, unless the mode is REPLACE)",
         "file existed with different content" + in, "files existed with different content" + in));
      add(lines, line(Status.WARNING, t.getFilesMissing(), "file is missing from the backup" + in, "files are missing from the backup" + in,
         "file was missing from the backup" + in, "files were missing from the backup" + in));
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   private static void add(List<ProcessSummaryLineInterface> lines, ProcessSummaryLine line)
   {
      if(line.getCount() != null && line.getCount() > 0)
      {
         lines.add(line);
      }
   }
}
