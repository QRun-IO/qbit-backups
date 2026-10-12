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
package com.kingsrook.qbits.backups.run;


import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.codec.TableArtifactWriter;


/*******************************************************************************
 ** What a failed run already wrote, so the engine can carry on instead of starting
 ** over (spec 9, Resume): the same run key and prefix, every table that finished
 ** kept as it is, and the interrupted table continuing from the last key of its
 ** last complete part.
 **
 ** @param runId the run row being resumed
 ** @param runKey the run key of the original attempt
 ** @param runPrefix the object prefix of the original attempt
 ** @param tables the state of each table that has any catalogued part
 *******************************************************************************/
public record ResumeState(Integer runId, String runKey, String runPrefix, Map<String, TableState> tables)
{

   /*******************************************************************************
    ** The state of one table.
    **
    ** @param complete the table finished (its last part carries the rows hash)
    ** @param parts the complete parts, in order
    ** @param rowsHash the rows hash, only when complete
    ** @param rows the rows in the complete parts
    ** @param artifactIds the catalog artifact rows of the parts
    *******************************************************************************/
   public record TableState(boolean complete, List<TableArtifactWriter.PartResult> parts, String rowsHash, long rows, List<Integer> artifactIds)
   {
      /*******************************************************************************
       ** The last key written, or null when there are no parts.
       *******************************************************************************/
      public String lastKey()
      {
         return (parts.isEmpty() ? null : parts.get(parts.size() - 1).lastKey());
      }
   }



   /*******************************************************************************
    ** The state of one table, or null when nothing of it was written.
    *******************************************************************************/
   public TableState table(String name)
   {
      return (tables.get(name));
   }
}
