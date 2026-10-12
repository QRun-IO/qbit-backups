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


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/*******************************************************************************
 ** What one verification found: counts, the first mismatches and warnings (capped,
 ** so a ruined bucket does not write megabytes of messages), and per-table detail
 ** for backups_verification.detailJson.  Messages never contain row values.
 *******************************************************************************/
class VerifyFindings
{
   static final int MAX_MESSAGES = 100;

   final List<String>              mismatchMessages = new ArrayList<>();
   final List<String>              warnings         = new ArrayList<>();
   final List<Map<String, Object>> tables           = new ArrayList<>();
   final Map<String, Object>       extra            = new LinkedHashMap<>();

   int  mismatches;
   int  tablesChecked;
   long rowsChecked;
   int  objectsChecked;
   int  filesChecked;
   int  filesSkipped;



   /*******************************************************************************
    ** Something did not match.
    *******************************************************************************/
   void mismatch(String message)
   {
      mismatches++;
      if(mismatchMessages.size() < MAX_MESSAGES)
      {
         mismatchMessages.add(message);
      }
   }



   /*******************************************************************************
    ** Something worth knowing that is not a failure.
    *******************************************************************************/
   void warn(String message)
   {
      if(warnings.size() < MAX_MESSAGES)
      {
         warnings.add(message);
      }
   }



   /*******************************************************************************
    ** The mismatches, then the warnings.
    *******************************************************************************/
   List<String> messages()
   {
      List<String> all = new ArrayList<>(mismatchMessages);
      all.addAll(warnings);
      return (all);
   }



   /*******************************************************************************
    ** PASSED, PASSED_WITH_WARNINGS or FAILED.
    *******************************************************************************/
   String status()
   {
      if(mismatches > 0)
      {
         return ("FAILED");
      }
      return (warnings.isEmpty() ? "PASSED" : "PASSED_WITH_WARNINGS");
   }
}
