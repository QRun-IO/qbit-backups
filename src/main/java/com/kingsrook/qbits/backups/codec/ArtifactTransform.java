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


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;


/*******************************************************************************
 ** A reversible transform applied to the bytes of an artifact after compression,
 ** before they reach storage.  The only implementation is client-side encryption
 ** (the streaming cipher of the QQQ crypto package); IDENTITY is the default.
 *******************************************************************************/
public interface ArtifactTransform
{
   /** No transform. */
   ArtifactTransform IDENTITY = new ArtifactTransform()
   {
      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public OutputStream wrap(OutputStream out)
      {
         return (out);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public InputStream unwrap(InputStream in)
      {
         return (in);
      }
   };



   /***************************************************************************
    ** Wrap the stream going to storage.
    ***************************************************************************/
   OutputStream wrap(OutputStream out) throws IOException;


   /***************************************************************************
    ** Wrap a stream read from storage, to undo the transform.
    ***************************************************************************/
   InputStream unwrap(InputStream in) throws IOException;


   /***************************************************************************
    ** Whether this transform encrypts.
    ***************************************************************************/
   default boolean encrypts()
   {
      return (false);
   }


   /***************************************************************************
    ** File name suffix added to artifact keys, e.g. ".tink".
    ***************************************************************************/
   default String keySuffix()
   {
      return ("");
   }
}
