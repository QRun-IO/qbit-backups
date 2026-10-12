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


/*******************************************************************************
 ** A verification could not be carried out (as opposed to carried out and found
 ** a mismatch): the storage is unreachable, there is no key to decrypt with.  The
 ** verification ends with status ERROR.
 *******************************************************************************/
public class VerifyException extends RuntimeException
{
   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public VerifyException(String message)
   {
      super(message);
   }



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public VerifyException(String message, Throwable cause)
   {
      super(message, cause);
   }
}
