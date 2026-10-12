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


import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


/*******************************************************************************
 ** An output stream that counts and SHA-256 hashes the bytes passing through.
 ** Closing it closes the stream underneath.  The hash is available after close()
 ** (or any time, as the hash of the bytes so far).
 *******************************************************************************/
public class HashingOutputStream extends FilterOutputStream
{
   private final MessageDigest digest;
   private long                count = 0;
   private String              hex;



   /*******************************************************************************
    ** Constructor.
    *******************************************************************************/
   public HashingOutputStream(OutputStream out)
   {
      super(out);
      this.digest = newSha256();
   }



   /*******************************************************************************
    ** A new SHA-256 digest.
    *******************************************************************************/
   public static MessageDigest newSha256()
   {
      try
      {
         return (MessageDigest.getInstance("SHA-256"));
      }
      catch(NoSuchAlgorithmException e)
      {
         throw (new IllegalStateException("SHA-256 is not available", e));
      }
   }



   /*******************************************************************************
    ** Hex of a digest result.
    *******************************************************************************/
   public static String hex(byte[] bytes)
   {
      return (HexFormat.of().formatHex(bytes));
   }



   /*******************************************************************************
    ** SHA-256 of a byte array, as hex.
    *******************************************************************************/
   public static String sha256Hex(byte[] bytes)
   {
      return (hex(newSha256().digest(bytes)));
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void write(int b) throws IOException
   {
      out.write(b);
      digest.update((byte) b);
      count++;
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void write(byte[] b, int off, int len) throws IOException
   {
      out.write(b, off, len);
      digest.update(b, off, len);
      count += len;
   }



   /*******************************************************************************
    ** {@inheritDoc}
    *******************************************************************************/
   @Override
   public void close() throws IOException
   {
      super.close();
      if(hex == null)
      {
         hex = hex(digest.digest());
      }
   }



   /*******************************************************************************
    ** The SHA-256 (hex) of everything written; valid after close().
    *******************************************************************************/
   public String getSha256()
   {
      return (hex);
   }



   /*******************************************************************************
    ** The number of bytes written.
    *******************************************************************************/
   public long getCount()
   {
      return (count);
   }
}
