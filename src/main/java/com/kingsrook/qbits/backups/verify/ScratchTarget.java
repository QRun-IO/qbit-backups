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


import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** Where a TEST_RESTORE verification restores to (design spec section 10.1).  The
 ** target builds a scratch copy of each table from the source table's metadata,
 ** takes the restored rows, reads them back in primary key order, and is dropped
 ** afterwards.  Nothing a scratch target does may touch the real tables.
 **
 ** Implementations: MEMORY (MemoryScratchTarget, small apps and CI) and RDBMS_SCHEMA
 ** (RdbmsSchemaScratchTarget, a stub until the restore agent's writer ships).
 *******************************************************************************/
public interface ScratchTarget
{
   /***************************************************************************
    ** The target type: MEMORY, RDBMS_SCHEMA or SEPARATE_DATABASE.
    ***************************************************************************/
   String type();


   /***************************************************************************
    ** A description for backups_verification.scratchTarget, for example
    ** "memory:qqq_verify_20261012t020000z_7f3a".
    ***************************************************************************/
   String describe(String runKey);


   /***************************************************************************
    ** Create the scratch copy of a table for a run.
    ***************************************************************************/
   ScratchTable create(QTableMetaData source, String runKey) throws QException;


   /***************************************************************************
    ** Drop everything created for the run.  Safe to call more than once.
    ***************************************************************************/
   void drop(String runKey) throws QException;
}
