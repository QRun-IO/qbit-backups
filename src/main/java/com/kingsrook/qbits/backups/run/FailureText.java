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


import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/*******************************************************************************
 ** Safe text for a failure: what is persisted on a run row (error_message and
 ** error_detail) and shown in job output.
 **
 ** Exception messages from storage providers, drivers and readers can carry
 ** credentials (URL user-info, presigned query strings, key=value secrets,
 ** Authorization headers, AWS access key ids, PEM blocks) and row values (the
 ** Postgres "Key (col)=(value)" and "Detail:" fragments).  sanitize() redacts
 ** those and keeps the rest (class names, object keys, bucket names), which is
 ** what an operator needs to act on a failure without reading the server log.
 *******************************************************************************/
public final class FailureText
{
   /** Redaction marker. */
   public static final String REDACTED = "[REDACTED]";

   /** The longest error_detail written. */
   public static final int MAX_DETAIL_CHARS = 8192;

   /** The longest exception message kept in error_message. */
   public static final int MAX_SUMMARY_MESSAGE_CHARS = 500;

   /** Stack frames of the root cause kept in error_detail. */
   public static final int ROOT_CAUSE_FRAMES = 8;

   private static final int MAX_CAUSES = 20;

   private static final String SECRET_NAME = "[\\w.-]*(?:password|passwd|pwd|secret|token|credential|signature|api[_-]?key|access[_-]?key|private[_-]?key)[\\w.-]*";

   private static final Pattern PEM_BLOCK     = Pattern.compile("-----BEGIN [A-Z0-9 ]+-----.*?(?:-----END [A-Z0-9 ]+-----|$)", Pattern.DOTALL);
   private static final Pattern URL_USERINFO  = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/\\s@?#]+@");
   private static final Pattern QUERY_PARAM   = Pattern.compile("(?i)([?&;](?:X-Amz-[\\w-]+|Signature|Credential|AWSAccessKeyId|Policy|sig|key|" + SECRET_NAME + ")=)[^&\\s\"'<>]*");
   private static final Pattern PG_KEY_VALUE  = Pattern.compile("(?i)\\b(Key \\([^)]*\\)=\\().*?(\\))(?=\\s|$|[.,;:])");
   private static final Pattern PG_DETAIL     = Pattern.compile("(?i)\\b(Detail:|Failing row contains)[^\\r\\n]*");
   private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\b((?:Proxy-)?Authorization\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^\\r\\n,;]+)");
   private static final Pattern BEARER        = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{6,}");
   private static final Pattern SECRET_JSON   = Pattern.compile("(?i)(\"" + SECRET_NAME + "\"\\s*:\\s*)\"[^\"]*\"");
   private static final Pattern SECRET_ASSIGN = Pattern.compile("(?i)(?<![\\w.-])((?:" + SECRET_NAME + "|key)\\s*=\\s*)(?!\\[REDACTED)(\"[^\"]*\"|'[^']*'|[^\\s&,;)\\]}]+)");
   private static final Pattern AWS_KEY_ID    = Pattern.compile("\\b(?:AKIA|ASIA|AGPA|AIDA|AROA|ANPA|ANVA|AIPA)[A-Z0-9]{16}\\b");

   private static final String R = Matcher.quoteReplacement(REDACTED);



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private FailureText()
   {
   }



   /*******************************************************************************
    ** Redact secrets and row values from a message.  Null stays null.
    *******************************************************************************/
   public static String sanitize(String text)
   {
      if(text == null)
      {
         return (null);
      }
      String s = PEM_BLOCK.matcher(text).replaceAll(Matcher.quoteReplacement("[REDACTED PEM]"));
      s = URL_USERINFO.matcher(s).replaceAll("$1" + R + "@");
      s = QUERY_PARAM.matcher(s).replaceAll("$1" + R);
      s = PG_KEY_VALUE.matcher(s).replaceAll("$1" + R + "$2");
      s = PG_DETAIL.matcher(s).replaceAll("$1 " + R);
      s = AUTHORIZATION.matcher(s).replaceAll("$1" + R);
      s = BEARER.matcher(s).replaceAll("$1 " + R);
      s = SECRET_JSON.matcher(s).replaceAll("$1\"" + R + "\"");
      s = SECRET_ASSIGN.matcher(s).replaceAll("$1" + R);
      s = AWS_KEY_ID.matcher(s).replaceAll(R);
      return (s);
   }



   /*******************************************************************************
    ** The short error_message: "Backup failed: SimpleClass: sanitized message".
    *******************************************************************************/
   public static String summary(Throwable t)
   {
      return (summary("Backup failed", t));
   }



   /*******************************************************************************
    ** A short message: "prefix: SimpleClass: sanitized message" (bounded).
    *******************************************************************************/
   public static String summary(String prefix, Throwable t)
   {
      String message = sanitize(t.getMessage());
      if(message == null || message.isBlank())
      {
         return (prefix + ": " + t.getClass().getSimpleName());
      }
      message = message.strip();
      if(message.length() > MAX_SUMMARY_MESSAGE_CHARS)
      {
         message = message.substring(0, MAX_SUMMARY_MESSAGE_CHARS) + "...";
      }
      return (prefix + ": " + t.getClass().getSimpleName() + ": " + message);
   }



   /*******************************************************************************
    ** The error_detail: the exception class and sanitized message, each cause
    ** ("Caused by: ..."), then the top stack frames of the root cause, bounded to
    ** MAX_DETAIL_CHARS.  Frames are class, method, file and line only (no values).
    *******************************************************************************/
   public static String detail(Throwable t)
   {
      StringBuilder  out  = new StringBuilder(line(t));
      Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      seen.add(t);
      Throwable root  = t;
      Throwable cause = t.getCause();
      while(cause != null && seen.size() < MAX_CAUSES && seen.add(cause))
      {
         out.append("\nCaused by: ").append(line(cause));
         root = cause;
         cause = cause.getCause();
      }

      List<StackTraceElement> frames = List.of(root.getStackTrace());
      int                     shown  = Math.min(ROOT_CAUSE_FRAMES, frames.size());
      if(shown > 0)
      {
         out.append("\nRoot cause stack (top ").append(shown).append(" of ").append(frames.size()).append(" frames):");
         for(StackTraceElement frame : frames.subList(0, shown))
         {
            out.append("\n\tat ").append(frame);
         }
      }

      if(out.length() > MAX_DETAIL_CHARS)
      {
         String marker = "\n... (truncated)";
         out.setLength(MAX_DETAIL_CHARS - marker.length());
         out.append(marker);
      }
      return (out.toString());
   }



   /*******************************************************************************
    ** "fully.qualified.Class: sanitized message".
    *******************************************************************************/
   private static String line(Throwable t)
   {
      String message = sanitize(t.getMessage());
      return (message == null ? t.getClass().getName() : t.getClass().getName() + ": " + message);
   }
}
