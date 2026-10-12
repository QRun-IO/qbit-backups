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


/*******************************************************************************
 ** The result of verifying one run at one level; also what was written to the
 ** backups_verification row.
 **
 ** @param verificationId the backups_verification id
 ** @param runId the backups_run id
 ** @param level STORAGE, CONTENT or TEST_RESTORE
 ** @param status PASSED, PASSED_WITH_WARNINGS, FAILED or ERROR
 ** @param tablesChecked tables checked
 ** @param rowsChecked rows decoded and counted
 ** @param mismatches how many things did not match (the messages list is capped)
 ** @param objectsChecked storage objects examined
 ** @param filesChecked file-store objects examined (head, or read and re-hashed)
 ** @param filesSkipped file-store objects skipped because they were verified recently
 ** @param messages the first mismatches and warnings, in plain words
 ** @param scratchTarget for TEST_RESTORE, the scratch target used
 *******************************************************************************/
public record VerificationOutcome(Integer verificationId, Integer runId, String level, String status, int tablesChecked, long rowsChecked, int mismatches,
                                  int objectsChecked, int filesChecked, int filesSkipped, List<String> messages, String scratchTarget)
{
   /*******************************************************************************
    ** Whether the verification passed (with or without warnings).
    *******************************************************************************/
   public boolean passed()
   {
      return ("PASSED".equals(status) || "PASSED_WITH_WARNINGS".equals(status));
   }
}
