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


import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;


/*******************************************************************************
 ** How a restore writes (spec 5.3 and 5.4).  The default implementation uses the
 ** plain Insert, Update and Delete actions, and selects the core RestoreWriteFlag
 ** (QRun-IO/qqq#1111) and the core key-sequence resync (#1112) reflectively when the
 ** running QQQ has them.  A backend specific writer can be supplied on the request.
 **
 ** Whatever the implementation, record security, field security, unique keys and
 ** required-field validation still apply to every write.
 *******************************************************************************/
public interface RestoreWriter
{
   /*******************************************************************************
    ** Insert records (restore mode).  Returns the output records, with any errors.
    *******************************************************************************/
   List<QRecord> insert(QTableMetaData table, List<QRecord> records, RestoreRequest options, QBackendTransaction transaction) throws QException;



   /*******************************************************************************
    ** Update records (restore mode).  Returns the output records, with any errors.
    *******************************************************************************/
   List<QRecord> update(QTableMetaData table, List<QRecord> records, RestoreRequest options, QBackendTransaction transaction) throws QException;



   /*******************************************************************************
    ** Delete records by primary key (REPLACE).  Returns the number deleted.
    *******************************************************************************/
   long delete(QTableMetaData table, List<Serializable> primaryKeys, RestoreRequest options, QBackendTransaction transaction) throws QException;



   /*******************************************************************************
    ** Whether the writes honor the core restore flag (so created and modified dates
    ** are kept as supplied).
    *******************************************************************************/
   boolean usesCoreRestoreFlag();



   /*******************************************************************************
    ** Whether key sequences can be resynced on this QQQ (the core hook is present).
    *******************************************************************************/
   boolean canResyncSequences();



   /*******************************************************************************
    ** Resync key sequences after explicit-id inserts.  Returns the names of the tables
    ** whose sequence was advanced.
    *******************************************************************************/
   List<String> resyncSequences(Collection<QTableMetaData> tables) throws QException;
}
