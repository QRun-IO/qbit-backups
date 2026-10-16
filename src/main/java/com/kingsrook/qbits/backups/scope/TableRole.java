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

package com.kingsrook.qbits.backups.scope;


/*******************************************************************************
 ** What a table is for, as far as backups are concerned (spec 3.2).  Plans select
 ** tables by role.
 *******************************************************************************/
public enum TableRole
{
   /** Business data (included by default). */
   DATA,
   /** Audit tables (included by default, configurable). */
   AUDIT,
   /** Rebuildable from other data: search indexes, rollups, caches. Excluded by default. */
   DERIVED,
   /** Sessions, idempotency keys, scheduler internals. Excluded by default. */
   EPHEMERAL,
   /** Tokens, salts, credentials. NEVER backed up, even when selected explicitly. */
   SECRET,
   /** The tables of this qbit: the catalog is rebuilt from manifests. */
   BACKUPS,
   /** Tables backed by another system (API), or by a backend that cannot be queried (storage-only: never read, even when selected). Excluded by default. */
   EXTERNAL
}
