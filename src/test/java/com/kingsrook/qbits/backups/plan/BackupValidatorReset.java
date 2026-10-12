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

package com.kingsrook.qbits.backups.plan;


import com.kingsrook.qbits.backups.schedule.BackupScheduledRunProcess;


/*******************************************************************************
 ** Puts the static hooks of the plan and schedule code back to their defaults, so
 ** a test that moves one does not leak it into the next.
 *******************************************************************************/
public final class BackupValidatorReset
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupValidatorReset()
   {
   }



   /*******************************************************************************
    ** Reset.
    *******************************************************************************/
   public static void reset()
   {
      BackupPlanValidator.setEncryptionAvailable(false);
      BackupScheduledRunProcess.setOwnerHasAdminCheck(null);
   }
}
