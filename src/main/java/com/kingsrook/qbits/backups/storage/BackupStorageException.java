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

package com.kingsrook.qbits.backups.storage;


/*******************************************************************************
 ** A storage failure, with a reason that mirrors the StorageException reasons
 ** of the object-storage qbit.
 *******************************************************************************/
public class BackupStorageException extends RuntimeException
{
   private final Reason reason;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public BackupStorageException(Reason reason, String message)
   {
      super(message);
      this.reason = reason;
   }



   /*******************************************************************************
    ** Constructor with a cause.
    *******************************************************************************/
   public BackupStorageException(Reason reason, String message, Throwable cause)
   {
      super(message, cause);
      this.reason = reason;
   }



   /*******************************************************************************
    ** Getter for reason
    *******************************************************************************/
   public Reason getReason()
   {
      return (reason);
   }



   /***************************************************************************
    ** Failure reasons.
    ***************************************************************************/
   public enum Reason
   {
      NOT_FOUND, ACCESS_DENIED, UNSUPPORTED, PRECONDITION_FAILED, THROTTLED, PROVIDER_ERROR
   }
}
