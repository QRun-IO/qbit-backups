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
 ** The RDBMS_SCHEMA scratch target (design spec section 10.1): a configured scratch
 ** backend whose dedicated database user may CREATE SCHEMA and DROP SCHEMA only for
 ** qqq_verify_*.  The qbit creates qqq_verify_(runKey), builds each table there
 ** from a DDL-only copy generated from QQQ metadata (columns by type, primary key,
 ** unique keys), restores through a temporary in-memory QTableMetaData clone that
 ** points at the scratch schema, and drops the schema.
 **
 ** STUB / TODO: this needs the restore agent's writer (the restore-mode insert,
 ** QRun-IO/qqq PR #1111 RestoreWriteFlag, and the table writer built on it) and the
 ** schema DDL generator.  Until those land, create() refuses with a clear message,
 ** so a plan that selects this target fails its TEST_RESTORE verification with an
 ** ERROR instead of silently passing.  Replace the body with the real
 ** implementation when the restore writer is available.
 *******************************************************************************/
public class RdbmsSchemaScratchTarget implements ScratchTarget
{
   public static final String TYPE = "RDBMS_SCHEMA";

   private final String backendName;



   /*******************************************************************************
    ** Constructor.
    **
    ** @param backendName the configured scratch RDBMS backend
    *******************************************************************************/
   public RdbmsSchemaScratchTarget(String backendName)
   {
      this.backendName = backendName;
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String type()
   {
      return (TYPE);
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public String describe(String runKey)
   {
      return (backendName + ":schema=qqq_verify_" + runKey.replaceAll("[^A-Za-z0-9]", "_").toLowerCase());
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public ScratchTable create(QTableMetaData source, String runKey) throws QException
   {
      // TODO: CREATE SCHEMA, generate the DDL-only table copy, and return a ScratchTable that restores
      //  through the restore writer.  Needs the restore agent's writer.
      throw (new QException("The RDBMS_SCHEMA scratch target is not implemented yet (it needs the restore writer); use the MEMORY scratch target."));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void drop(String runKey) throws QException
   {
      // TODO: DROP SCHEMA qqq_verify_(runKey) once create() is implemented.
   }
}
