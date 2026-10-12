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

package com.kingsrook.qbits.backups;


import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.kingsrook.qbits.backups.codec.ArtifactTransform;
import com.kingsrook.qbits.backups.events.BackupEvents;
import com.kingsrook.qbits.backups.metrics.BackupsMetrics;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupEngine;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.sla.SlaChecker;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Shared setup for the verification, SLA, alert, health and event tests: the
 ** blog instance, a synthetic clock, a recording alert sink, plans and runs.
 *******************************************************************************/
public final class VerifyTestSupport
{
   public static MutableClock       clock;
   public static RecordingAlertSink sink;



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private VerifyTestSupport()
   {
   }



   /*******************************************************************************
    ** Fresh instance with posts, a synthetic clock starting now, and a recording sink.
    *******************************************************************************/
   public static void setUp(int posts) throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(posts);
      SlaChecker.resetCaches();
      BackupsMetrics.resetCache();
      BackupEvents.setPublisher(null);
      clock = new MutableClock(Instant.now());
      sink = new RecordingAlertSink();
      BackupsRuntime.setClock(clock);
      BackupsRuntime.setAlertSink(sink);
   }



   /*******************************************************************************
    ** Insert a nightly plan; overrides replace the defaults.
    *******************************************************************************/
   public static QRecord plan(Map<String, Serializable> overrides) throws Exception
   {
      Map<String, Serializable> values = new LinkedHashMap<>();
      values.put("name", "Nightly");
      values.put("planKey", "nightly");
      values.put("isActive", true);
      values.put("scopeType", "INSTANCE");
      values.put("storageBucketAlias", "backups");
      values.put("slaHours", 26);
      values.put("verifySlaDays", 8);
      values.putAll(overrides);
      Integer id = new BackupCatalog().insert(BackupsTables.PLAN, values);
      return (new BackupCatalog().get(BackupsTables.PLAN, id).orElseThrow());
   }



   /*******************************************************************************
    ** The plan as it is now.
    *******************************************************************************/
   public static QRecord reload(QRecord plan) throws Exception
   {
      return (new BackupCatalog().get(BackupsTables.PLAN, plan.getValueInteger("id")).orElseThrow());
   }



   /*******************************************************************************
    ** The run as it is now.
    *******************************************************************************/
   public static QRecord run(Integer runId) throws Exception
   {
      return (new BackupCatalog().get(BackupsTables.RUN, runId).orElseThrow());
   }



   /*******************************************************************************
    ** Run the plan's backup.
    *******************************************************************************/
   public static BackupRunResult backup(QRecord plan) throws Exception
   {
      return (new BackupEngine().execute(BackupRunFactory.forPlan(plan, "MANUAL")));
   }



   /*******************************************************************************
    ** Run the plan's backup encrypted with a transform.
    *******************************************************************************/
   public static BackupRunResult backupEncrypted(QRecord plan, ArtifactTransform transform, String keyRef) throws Exception
   {
      return (new BackupEngine().execute(BackupRunFactory.forPlan(plan, "MANUAL").withTransform(transform, keyRef, "fingerprint-" + keyRef)));
   }



   /*******************************************************************************
    ** The part keys (relative to the run prefix) of a table in a run's manifest.
    *******************************************************************************/
   public static String firstPartKey(BackupRunResult result, String table) throws Exception
   {
      var manifest = com.kingsrook.qbits.backups.manifest.BackupManifest.read(BackupsTestUtils.BACKUPS.getBytes(result.getManifestKey()));
      return (result.getRunPrefix() + manifest.table(table).parts().get(0).key());
   }



   /***************************************************************************
    ** A toy "cipher" for tests: every byte is XORed with a key byte.  Different
    ** key bytes stand in for "the wrong key".
    ***************************************************************************/
   public static class XorTransform implements ArtifactTransform
   {
      private final int key;



      /*******************************************************************************
       ** Constructor.
       *******************************************************************************/
      public XorTransform(int key)
      {
         this.key = key;
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public OutputStream wrap(OutputStream out)
      {
         return (new FilterOutputStream(out)
         {
            /*******************************************************************************
             ** {@inheritDoc}
             *******************************************************************************/
            @Override
            public void write(int b) throws IOException
            {
               out.write((b ^ key) & 0xFF);
            }



            /*******************************************************************************
             ** {@inheritDoc}
             *******************************************************************************/
            @Override
            public void write(byte[] b, int off, int len) throws IOException
            {
               for(int i = 0; i < len; i++)
               {
                  write(b[off + i]);
               }
            }
         });
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public InputStream unwrap(InputStream in)
      {
         return (new FilterInputStream(in)
         {
            /*******************************************************************************
             ** {@inheritDoc}
             *******************************************************************************/
            @Override
            public int read() throws IOException
            {
               int b = super.read();
               return (b < 0 ? b : (b ^ key) & 0xFF);
            }



            /*******************************************************************************
             ** {@inheritDoc}
             *******************************************************************************/
            @Override
            public int read(byte[] b, int off, int len) throws IOException
            {
               int n = super.read(b, off, len);
               for(int i = 0; i < n; i++)
               {
                  b[off + i] = (byte) ((b[off + i] ^ key) & 0xFF);
               }
               return (n);
            }
         });
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public boolean encrypts()
      {
         return (true);
      }



      /*******************************************************************************
       ** {@inheritDoc}
       *******************************************************************************/
      @Override
      public String keySuffix()
      {
         return (".xor");
      }
   }
}
