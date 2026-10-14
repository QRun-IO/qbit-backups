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

package com.kingsrook.qbits.backups.demo;


import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qbits.backups.BackupsQBitConfig;
import com.kingsrook.qbits.backups.BackupsQBitProducer;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupEngine;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qbits.backups.scope.BackupTableMetaData;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.InMemoryBackupStorage;
import com.kingsrook.qqq.backend.core.actions.tables.InsertAction;
import com.kingsrook.qqq.backend.core.actions.tables.UpdateAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.instances.AbstractQQQApplication;
import com.kingsrook.qqq.backend.core.instances.QInstanceValidator;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QAuthenticationType;
import com.kingsrook.qqq.backend.core.model.metadata.QBackendMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.authentication.QAuthenticationMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QAppMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import com.kingsrook.qqq.backend.core.modules.backend.implementations.memory.MemoryBackendModule;
import com.kingsrook.qqq.middleware.javalin.QApplicationJavalinServer;


/*******************************************************************************
 ** A local demo (for screenshots and manual checks, never shipped): the memory
 ** backend, a small blog (authors, posts with media files), the Backups qbit under
 ** an Admin app with in-memory storage, and a fortnight of history made by running
 ** the real backup engine against a moving clock (including one run that fails
 ** because the storage refused writes).
 **
 ** <pre>
 ** ./mvnw -o test-compile exec:java -Dexec.classpathScope=test \
 **    -Dexec.mainClass=com.kingsrook.qbits.backups.demo.BackupsDemoServer -Dexec.args=8098
 ** </pre>
 *******************************************************************************/
public class BackupsDemoServer extends AbstractQQQApplication
{
   public static final String BACKEND = "memory";

   static final InMemoryBackupStorage BACKUPS = new InMemoryBackupStorage();
   static final InMemoryBackupStorage MEDIA   = new InMemoryBackupStorage();
   static final AtomicBoolean         STORAGE_DOWN = new AtomicBoolean(false);
   static final DemoClock             CLOCK        = new DemoClock();



   /*******************************************************************************
    ** A clock the seeder moves: the real time shifted by an offset, so a run still
    ** takes the real time it takes (durations are real) but happens "in the past".
    *******************************************************************************/
   static class DemoClock extends Clock
   {
      private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);



      /*******************************************************************************
       ** Set the time.
       *******************************************************************************/
      void set(Instant instant)
      {
         offset.set(Duration.between(Instant.now(), instant));
      }



      /*******************************************************************************
       ** Zone.
       *******************************************************************************/
      @Override
      public ZoneId getZone()
      {
         return (ZoneId.of("UTC"));
      }



      /*******************************************************************************
       ** Zone.
       *******************************************************************************/
      @Override
      public Clock withZone(ZoneId zone)
      {
         return (this);
      }



      /*******************************************************************************
       ** Now.
       *******************************************************************************/
      @Override
      public Instant instant()
      {
         return (Instant.now().plus(offset.get()));
      }
   }



   /*******************************************************************************
    ** The instance: a blog, an Admin app and the Backups qbit under it.
    *******************************************************************************/
   @Override
   public QInstance defineQInstance() throws QException
   {
      QInstance instance = new QInstance();
      instance.withInstanceDefaultAuthentication(new QAuthenticationMetaData().withName("anonymous").withType(QAuthenticationType.FULLY_ANONYMOUS));
      instance.addBackend(new QBackendMetaData().withName(BACKEND).withBackendType(MemoryBackendModule.class));

      QTableMetaData author = new QTableMetaData().withName("author").withLabel("Author").withBackendName(BACKEND).withPrimaryKeyField("id")
         .withRecordLabelFormatAndFields("%s", "name")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("name", QFieldType.STRING))
         .withField(new QFieldMetaData("bio", QFieldType.TEXT));
      QTableMetaData post = new QTableMetaData().withName("blogPost").withLabel("Blog Post").withBackendName(BACKEND).withPrimaryKeyField("id")
         .withRecordLabelFormatAndFields("%s", "title")
         .withField(new QFieldMetaData("id", QFieldType.INTEGER))
         .withField(new QFieldMetaData("authorId", QFieldType.INTEGER).withPossibleValueSourceName("author"))
         .withField(new QFieldMetaData("title", QFieldType.STRING))
         .withField(new QFieldMetaData("body", QFieldType.TEXT))
         .withField(new QFieldMetaData("price", QFieldType.DECIMAL))
         .withField(new QFieldMetaData("published", QFieldType.BOOLEAN))
         .withField(new QFieldMetaData("publishedOn", QFieldType.DATE))
         .withField(new QFieldMetaData("createDate", QFieldType.DATE_TIME))
         .withField(new QFieldMetaData("mediaKey", QFieldType.STRING))
         .withField(new QFieldMetaData("secretNote", QFieldType.STRING).withIsSensitive(true))
         .withSupplementalMetaData(new BackupTableMetaData().withFileReferenceFields(List.of("mediaKey")).withFileBucketAlias("media"));
      instance.addTable(author);
      instance.addTable(post);
      instance.addPossibleValueSource(QPossibleValueSource.newForTable("author"));
      instance.addApp(new QAppMetaData().withName("blog").withLabel("Blog").withIcon(new QIcon("article")).withChild(author).withChild(post));
      instance.addApp(new QAppMetaData().withName("admin").withLabel("Admin").withIcon(new QIcon("admin_panel_settings")));

      Map<String, BackupStorage> buckets = Map.of("backups", BACKUPS, "media", MEDIA);
      BackupsQBitConfig config = new BackupsQBitConfig().withBackendName(BACKEND).withInstanceName("blog-demo").withParentAppName("admin")
         .withStorageResolver(buckets::get).withOpenWhenAnonymous(true);
      new BackupsQBitProducer().withQBitConfig(config).withNamespace("backups").produce(instance);
      instance.setHomeAppName("backups");
      return (instance);
   }



   /*******************************************************************************
    ** Insert one record.
    *******************************************************************************/
   private static void insert(String table, Map<String, Serializable> values) throws QException
   {
      new InsertAction().execute(new InsertInput(table).withRecord(record(values)));
   }



   /*******************************************************************************
    ** A record of values.
    *******************************************************************************/
   private static QRecord record(Map<String, Serializable> values)
   {
      QRecord record = new QRecord();
      record.setValues(new LinkedHashMap<>(values));
      return (record);
   }



   /*******************************************************************************
    ** Add blog posts from first to last.
    *******************************************************************************/
   private static void addPosts(int first, int last, Instant when) throws QException
   {
      for(int i = first; i <= last; i++)
      {
         Map<String, Serializable> row = new LinkedHashMap<>();
         row.put("id", i);
         row.put("authorId", 1 + (i % 3));
         row.put("title", "Post " + i + ": notes on " + List.of("storage", "backups", "restores", "retention", "checksums", "scheduling").get(i % 6));
         row.put("body", ("Body of post " + i + ". Plain words about keeping data safe, repeated so it has some size. ").repeat(12));
         row.put("price", new BigDecimal("12.3400").add(BigDecimal.valueOf(i)));
         row.put("published", i % 4 != 0);
         row.put("publishedOn", java.time.LocalDate.of(2026, 1, 1).plusDays(i % 280));
         row.put("createDate", when);
         row.put("secretNote", "internal-note-" + i);
         if(i % 3 != 0)
         {
            String key = "media/photo-" + (i % 9) + ".jpg";
            row.put("mediaKey", key);
            if(MEDIA.getBytes(key) == null)
            {
               MEDIA.putBytes(key, ("photo bytes " + (i % 9) + " ").repeat(2000).getBytes());
            }
         }
         insert("blogPost", row);
      }
   }



   /*******************************************************************************
    ** Create a plan with the given values; returns the record.
    *******************************************************************************/
   private static QRecord plan(Map<String, Serializable> overrides, List<String[]> scope) throws Exception
   {
      Map<String, Serializable> values = new LinkedHashMap<>(Map.of("isActive", true, "storageBucketAlias", "backups"));
      values.putAll(overrides);
      BackupCatalog catalog = new BackupCatalog();
      Integer       id      = catalog.insert(BackupsTables.PLAN, values);
      for(String[] rule : scope)
      {
         catalog.insert(BackupsTables.PLAN_SCOPE, new LinkedHashMap<>(Map.of("backupPlanId", id, "action", "INCLUDE", "selectorType", rule[0], "selectorValue", rule[1])));
      }
      return (catalog.get(BackupsTables.PLAN, id).orElseThrow());
   }



   /*******************************************************************************
    ** Run a plan with the real engine, as it is right now (the clock decides when).
    *******************************************************************************/
   private static void run(Integer planId, String trigger) throws Exception
   {
      QRecord plan = new BackupCatalog().get(BackupsTables.PLAN, planId).orElseThrow();
      new BackupEngine().execute(BackupRunFactory.forPlan(plan, trigger));
   }



   /*******************************************************************************
    ** Seed: a fortnight of nightly runs, a weekly content plan, and a plan whose
    ** latest run fails because the storage refused writes.
    *******************************************************************************/
   static void seed(QInstance instance) throws Exception
   {
      QContext.init(instance, new QSession());
      BackupsRuntime.setClock(CLOCK);
      Instant today = Instant.now().truncatedTo(ChronoUnit.HOURS);

      CLOCK.set(today.minus(Duration.ofDays(15)));
      insert("author", Map.of("id", 1, "name", "Ada", "bio", "Writes about storage."));
      insert("author", Map.of("id", 2, "name", "Grace", "bio", "Writes about compilers and restores."));
      insert("author", Map.of("id", 3, "name", "Linus", "bio", ""));
      addPosts(1, 240, CLOCK.instant());

      QRecord nightly = plan(Map.of("name", "Nightly full backup", "planKey", "nightly", "scopeType", "INSTANCE", "slaHours", 26, "verifySlaDays", 8,
         "cronExpression", "0 0 2 * * ?", "cronTimeZoneId", "UTC", "retainDaily", 7, "retainWeekly", 4, "description", "Everything, every night, kept 7 daily and 4 weekly."), List.of());
      QRecord content = plan(Map.of("name", "Blog content weekly", "planKey", "blog-weekly", "scopeType", "TABLES", "slaHours", 170, "verifySlaDays", 14,
         "cronExpression", "0 0 3 ? * SUN", "cronTimeZoneId", "UTC", "description", "Authors and posts only, every Sunday."),
         List.of(new String[] { "TABLE", "author" }, new String[] { "TABLE", "blogPost" }));
      QRecord archive = plan(Map.of("name", "Posts to cold storage", "planKey", "posts-cold", "scopeType", "TABLES", "slaHours", 26, "format", "CSV",
         "description", "Posts as CSV for the archive bucket."), List.<String[]>of(new String[] { "TABLE", "blogPost" }));

      int post = 240;
      for(int day = 14; day >= 0; day--)
      {
         CLOCK.set(today.minus(Duration.ofDays(day)).minus(Duration.ofHours(today.atZone(ZoneId.of("UTC")).getHour())).plus(Duration.ofHours(2)));
         if(day > 0)
         {
            addPosts(post + 1, post + 4, CLOCK.instant());
            post += 4;
         }
         run(nightly.getValueInteger("id"), "SCHEDULED");
         if(day % 7 == 0)
         {
            CLOCK.set(CLOCK.instant().plus(Duration.ofHours(1)));
            run(content.getValueInteger("id"), "SCHEDULED");
         }
         if(day == 1)
         {
            run(archive.getValueInteger("id"), "MANUAL");
         }
      }

      // One run that fails for real: the archive bucket refuses writes.
      CLOCK.set(today.minus(Duration.ofHours(3)));
      STORAGE_DOWN.set(true);
      run(archive.getValueInteger("id"), "MANUAL");
      STORAGE_DOWN.set(false);

      // Verification, restore and prune (the scratch merge of PRs 5, 6 and 7): all real engine runs.
      Integer nightlyRun = new BackupCatalog().get(BackupsTables.PLAN, nightly.getValueInteger("id")).orElseThrow().getValueInteger("lastSuccessRunId");
      Integer weeklyRun  = new BackupCatalog().get(BackupsTables.PLAN, content.getValueInteger("id")).orElseThrow().getValueInteger("lastSuccessRunId");
      CLOCK.set(today.minus(Duration.ofHours(2)));
      new com.kingsrook.qbits.backups.verify.BackupVerifier().verifyThrough(nightlyRun, "TEST_RESTORE", "MANUAL", "james");
      new com.kingsrook.qbits.backups.verify.BackupVerifier().verifyThrough(weeklyRun, "CONTENT", "MANUAL", "james");
      new com.kingsrook.qbits.backups.verify.BackupVerifier().verify(nightlyRun - 1, "STORAGE", "SCHEDULED", "system:scheduler");

      CLOCK.set(today.minus(Duration.ofHours(1)));
      new com.kingsrook.qbits.backups.restore.RestoreEngine().review(new com.kingsrook.qbits.backups.restore.RestoreRequest().withSourceRunId(nightlyRun).withMode("UPSERT")
         .withTables(List.of("blogPost")).withRequestedBy("james"));
      new UpdateAction().execute(new UpdateInput("blogPost").withRecord(new QRecord().withValue("id", 1).withValue("title", "Post 1: edited after the last backup")));
      new com.kingsrook.qbits.backups.restore.RestoreEngine().execute(new com.kingsrook.qbits.backups.restore.RestoreRequest().withSourceRunId(nightlyRun).withMode("UPSERT")
         .withTables(List.of("blogPost")).withRequestedBy("james"));
      try
      {
         new com.kingsrook.qbits.backups.prune.BackupPruner().prune(new BackupCatalog().get(BackupsTables.PLAN, nightly.getValueInteger("id")).orElseThrow(), "james");
      }
      catch(Exception e)
      {
         System.out.println("prune skipped: " + e);
      }
      new com.kingsrook.qbits.backups.sla.SlaChecker().checkAll();
      new UpdateAction().execute(new UpdateInput("blogPost").withRecord(new QRecord().withValue("id", 2).withValue("title", "Post 2: edited after the restore")));
      CLOCK.set(Instant.now());
      BackupsRuntime.setClock(Clock.systemUTC());
      QContext.clear();
   }



   /*******************************************************************************
    ** Run.
    *******************************************************************************/
   public static void main(String[] args) throws Exception
   {
      int port = args.length > 0 ? Integer.parseInt(args[0]) : 8098;
      BACKUPS.withClock(CLOCK).withFailWritesFor(key -> STORAGE_DOWN.get());

      BackupsDemoServer application = new BackupsDemoServer();
      QInstance         seedInstance = application.defineQInstance();
      new QInstanceValidator().validate(seedInstance);
      seed(seedInstance);

      new QApplicationJavalinServer(application).withPort(port).withServeFrontendNext(true).start();
      System.out.println("Backups demo on http://localhost:" + port + "/app/admin/backups");
   }
}
