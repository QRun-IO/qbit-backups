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


import java.util.List;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Encodes a record to one line of text, and decodes it back.
 *******************************************************************************/
public interface RowCodec
{

   /***************************************************************************
    ** The field names written, in order.
    ***************************************************************************/
   List<String> getFieldNames();


   /***************************************************************************
    ** A header line to write at the start of each part, or null.
    ***************************************************************************/
   String header();


   /***************************************************************************
    ** One line, without the trailing newline.
    ***************************************************************************/
   String encode(QRecord record);


   /***************************************************************************
    ** Read one line back into a record.
    ***************************************************************************/
   QRecord decode(String line) throws QException;
}
