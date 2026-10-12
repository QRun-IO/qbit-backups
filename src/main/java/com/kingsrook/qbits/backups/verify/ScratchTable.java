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


import java.util.List;
import java.util.function.Consumer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** One restored table inside a scratch target.
 *******************************************************************************/
public interface ScratchTable
{
   /***************************************************************************
    ** Restore rows into the scratch table, preserving their primary keys.
    ***************************************************************************/
   void insert(List<QRecord> records) throws QException;


   /***************************************************************************
    ** Read every row back in primary key order, a page at a time.
    ***************************************************************************/
   void readInPrimaryKeyOrder(int pageSize, Consumer<QRecord> consumer) throws QException;
}
