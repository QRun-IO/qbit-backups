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

package com.kingsrook.qbits.backups.run;


import java.io.IOException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Unit tests of the failure text sanitizer, summary and detail.
 *******************************************************************************/
class FailureTextTest
{

   /*******************************************************************************
    ** Null and plain text pass through; object keys and bucket names are kept.
    *******************************************************************************/
   @Test
   void testPlainTextIsKept()
   {
      assertThat(FailureText.sanitize(null)).isNull();
      String plain = "Query is not implemented in this module: PreviewDownloadBackend (bucket kof22-backups, key qqq-backups/kof22/nightly/2026/10/16/run/tables/a/part-00001.jsonl.gz)";
      assertThat(FailureText.sanitize(plain)).isEqualTo(plain);
      assertThat(FailureText.sanitize("objectKey=qqq-backups/x/y.gz bucket=kof22-backups")).isEqualTo("objectKey=qqq-backups/x/y.gz bucket=kof22-backups");
   }



   /*******************************************************************************
    ** URL user-info and presigned query parameters are redacted; host and path kept.
    *******************************************************************************/
   @Test
   void testUrls()
   {
      assertThat(FailureText.sanitize("PUT https://user:pass@minio.lan/bucket/a/b.gz?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2Fx&X-Amz-Signature=abc123&partNumber=2 failed"))
         .isEqualTo("PUT https://[REDACTED]@minio.lan/bucket/a/b.gz?X-Amz-Algorithm=[REDACTED]&X-Amz-Credential=[REDACTED]&X-Amz-Signature=[REDACTED]&partNumber=2 failed");
      assertThat(FailureText.sanitize("jdbc:postgresql://app:hunter2@db:5432/app")).isEqualTo("jdbc:postgresql://[REDACTED]@db:5432/app");
      assertThat(FailureText.sanitize("https://h/p?Signature=s1&Expires=1&AWSAccessKeyId=k&access_token=t&key=v"))
         .isEqualTo("https://h/p?Signature=[REDACTED]&Expires=1&AWSAccessKeyId=[REDACTED]&access_token=[REDACTED]&key=[REDACTED]");
   }



   /*******************************************************************************
    ** key=value and JSON secrets, Authorization and Bearer values.
    *******************************************************************************/
   @Test
   void testAssignmentsAndHeaders()
   {
      assertThat(FailureText.sanitize("password=p1 secret=\"s 2\" token='t3' key=k4 accessKey=a5 secretKey=s6 apiKey=x7 db.password = p8, other=ok"))
         .isEqualTo("password=[REDACTED] secret=[REDACTED] token=[REDACTED] key=[REDACTED] accessKey=[REDACTED] secretKey=[REDACTED] apiKey=[REDACTED] db.password = [REDACTED], other=ok");
      assertThat(FailureText.sanitize("{\"clientSecret\": \"abc\", \"name\": \"keep\"}")).isEqualTo("{\"clientSecret\": \"[REDACTED]\", \"name\": \"keep\"}");
      assertThat(FailureText.sanitize("Authorization: Bearer eyJhbGciOi.payload.sig\nnext line")).isEqualTo("Authorization: [REDACTED]\nnext line");
      assertThat(FailureText.sanitize("sent Bearer abcdefghijk to the API")).isEqualTo("sent Bearer [REDACTED] to the API");
      assertThat(FailureText.sanitize("id AKIAIOSFODNN7EXAMPLE and ASIAIOSFODNN7EXAMPLE")).isEqualTo("id [REDACTED] and [REDACTED]");
   }



   /*******************************************************************************
    ** PEM blocks (terminated or not) and Postgres row values.
    *******************************************************************************/
   @Test
   void testPemAndRowValues()
   {
      assertThat(FailureText.sanitize("bad key -----BEGIN PRIVATE KEY-----\nMIIabc\n-----END PRIVATE KEY----- here")).isEqualTo("bad key [REDACTED PEM] here");
      assertThat(FailureText.sanitize("cut -----BEGIN RSA PRIVATE KEY-----\nMIIabc")).isEqualTo("cut [REDACTED PEM]");
      assertThat(FailureText.sanitize("duplicate key value violates unique constraint \"u_email\" Key (email)=(ada@example.com) already exists."))
         .isEqualTo("duplicate key value violates unique constraint \"u_email\" Key (email)=([REDACTED]) already exists.");
      assertThat(FailureText.sanitize("ERROR: null value in column \"x\"\n  Detail: Failing row contains (1, ada, secret-row).\n  Where: x"))
         .isEqualTo("ERROR: null value in column \"x\"\n  Detail: [REDACTED]\n  Where: x");
   }



   /*******************************************************************************
    ** The summary is short, sanitized and bounded; no message falls back to the class.
    *******************************************************************************/
   @Test
   void testSummary()
   {
      assertThat(FailureText.summary(new IllegalStateException("Query is not implemented in this module: PreviewDownloadBackend")))
         .isEqualTo("Backup failed: IllegalStateException: Query is not implemented in this module: PreviewDownloadBackend");
      assertThat(FailureText.summary(new NullPointerException())).isEqualTo("Backup failed: NullPointerException");
      assertThat(FailureText.summary("Could not create the run record", new IOException(" password=x "))).isEqualTo("Could not create the run record: IOException: password=[REDACTED]");
      assertThat(FailureText.summary(new IOException("x".repeat(2000)))).hasSize("Backup failed: IOException: ".length() + FailureText.MAX_SUMMARY_MESSAGE_CHARS + 3);
   }



   /*******************************************************************************
    ** The detail has the class, the cause chain and the root cause frames, bounded.
    *******************************************************************************/
   @Test
   void testDetail()
   {
      SQLException root   = new SQLException("Key (id)=(4242xyz) already exists.");
      IOException  middle = new IOException("write failed password=pwsecret", root);
      RuntimeException top = new RuntimeException("Backup of t failed", middle);
      String detail = FailureText.detail(top);
      assertThat(detail).startsWith("java.lang.RuntimeException: Backup of t failed\nCaused by: java.io.IOException: write failed password=[REDACTED]\nCaused by: java.sql.SQLException: Key (id)=([REDACTED]) already exists.\nRoot cause stack (top ");
      assertThat(detail).contains("\tat com.kingsrook.qbits.backups.run.FailureTextTest.testDetail(FailureTextTest.java:");
      assertThat(detail.split("\n\tat ")).hasSize(FailureText.ROOT_CAUSE_FRAMES + 1);
      assertThat(detail).doesNotContain("4242xyz", "pwsecret");

      IOException noFrames = new IOException((String) null);
      noFrames.setStackTrace(new StackTraceElement[0]);
      assertThat(FailureText.detail(noFrames)).isEqualTo("java.io.IOException");

      String huge = FailureText.detail(new IOException("y".repeat(20_000)));
      assertThat(huge).hasSize(FailureText.MAX_DETAIL_CHARS).endsWith("... (truncated)");
   }



   /*******************************************************************************
    ** A cause cycle does not loop.
    *******************************************************************************/
   @Test
   void testCauseCycle()
   {
      CyclicException a = new CyclicException("a");
      CyclicException b = new CyclicException("b");
      a.setCause(b);
      b.setCause(a);
      assertThat(FailureText.detail(a)).startsWith("com.kingsrook.qbits.backups.run.FailureTextTest$CyclicException: a\nCaused by: com.kingsrook.qbits.backups.run.FailureTextTest$CyclicException: b\nRoot cause stack");
   }



   /***************************************************************************
    ** An exception whose cause can be set to form a cycle.
    ***************************************************************************/
   private static class CyclicException extends Exception
   {
      private Throwable cause;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      CyclicException(String message)
      {
         super(message);
      }



      /*******************************************************************************
       ** Set the cause.
       *******************************************************************************/
      void setCause(Throwable cause)
      {
         this.cause = cause;
      }



      /*******************************************************************************
       ** The cause.
       *******************************************************************************/
      @Override
      public synchronized Throwable getCause()
      {
         return (cause);
      }
   }
}
