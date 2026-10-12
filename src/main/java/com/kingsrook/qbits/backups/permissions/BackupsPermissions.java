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

package com.kingsrook.qbits.backups.permissions;


/*******************************************************************************
 ** Permission names (design spec section 11.4).
 *******************************************************************************/
public final class BackupsPermissions
{
   /** The app, every backups table (read-only), the home. */
   public static final String VIEW    = "backups.view";
   /** Run backup now, back up records, verify, cancel and resume. */
   public static final String RUN     = "backups.run";
   /** Restore (insert-new and upsert), download, cancel restore. */
   public static final String RESTORE = "backups.restore";
   /** Edit plans, prune, replace restores, import catalog, test destination. */
   public static final String ADMIN   = "backups.admin";



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private BackupsPermissions()
   {
   }
}
