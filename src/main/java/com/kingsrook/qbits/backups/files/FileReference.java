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

package com.kingsrook.qbits.backups.files;


/*******************************************************************************
 ** A reference from a record field to a file in object storage.
 **
 ** @param tableName the referencing table
 ** @param recordKey the referencing record primary key (as text)
 ** @param field the referencing field
 ** @param bucketAlias the storage bucket alias the file lives in
 ** @param key the object key
 ** @param knownSha256 the content hash when the reference provider knows it (a content-addressed key or a stored checksum), else null
 ** @param contentType the content type when known, else null
 *******************************************************************************/
public record FileReference(String tableName, String recordKey, String field, String bucketAlias, String key, String knownSha256, String contentType)
{
}
