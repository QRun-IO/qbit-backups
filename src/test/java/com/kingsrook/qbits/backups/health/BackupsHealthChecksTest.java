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

package com.kingsrook.qbits.backups.health;


import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.VerifyTestSupport;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.plan.DestinationProbe;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.storage.InMemoryBackupStorage;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qqq.backend.core.actions.customizers.QCodeLoader;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.health.HealthCategory;
import com.kingsrook.qqq.backend.core.health.HealthCheck;
import com.kingsrook.qqq.backend.core.health.HealthCheckInput;
import com.kingsrook.qqq.backend.core.health.HealthCheckKind;
import com.kingsrook.qqq.backend.core.health.HealthCheckMetaData;
import com.kingsrook.qqq.backend.core.health.HealthCheckOutcome;
import com.kingsrook.qqq.backend.core.health.HealthChecksMetaData;
import com.kingsrook.qqq.backend.core.health.HealthStatus;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.session.QSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** Central registration, migrated SLA rules, read-only probes and secret safety.
 *******************************************************************************/
class BackupsHealthChecksTest
{
   private static final String SECRET = "S3NT1NEL-secret";

   private final BackupCatalog catalog = new BackupCatalog();
   private ReadOnlyStorage storage;



   /*******************************************************************************
    ** All probes use memory storage; the resolver represents credential injection.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(3);
      storage = new ReadOnlyStorage(SECRET);
      BackupsRuntime.getConfig().withStorageResolver(alias ->
      {
         assertThat(alias).as("never resolve prune credentials").isNotEqualTo("prune");
         return (storage);
      });
   }



   /*******************************************************************************
    ** Use the actual producer registry, including its reflective supplier loading.
    *******************************************************************************/
   @Test
   void testRegistrationAndDynamicPlans() throws Exception
   {
      HealthChecksMetaData registry = HealthChecksMetaData.ofOrWithNew(QContext.getQInstance());
      assertThat(registry.getChecks()).extracting(HealthCheckMetaData::getName).containsExactly("backups.sla");
      assertThat(registry.getSuppliers()).singleElement().satisfies(supplier ->
      {
         assertThat(supplier.getNamePrefix()).isEqualTo("backups.destination");
         assertThat(supplier.getComponent()).isEqualTo("backups");
      });
      QRecord active = VerifyTestSupport.plan(Map.of());
      VerifyTestSupport.plan(Map.of("name", "Off", "planKey", "off", "isActive", false));
      List<HealthCheckMetaData> checks = registry.resolveAll(QContext.getQInstance());
      assertThat(checks).hasSize(2).allSatisfy(check ->
      {
         assertThat(check.getComponent()).isEqualTo("backups");
         assertThat(check.getKind()).isEqualTo(HealthCheckKind.DEPENDENCY);
         assertThat(check.getCategory()).isEqualTo(HealthCategory.STORAGE);
         assertThat(check.getIncludeInReadiness()).isFalse();
         assertThat(check.getName()).matches("[a-z0-9]+([._-][a-z0-9]+)*");
      });
      assertThat(checks.get(0).getIntervalSeconds()).isEqualTo(900);
      assertThat(checks.get(0).getTimeoutMs()).isEqualTo(5000);
      assertThat(QCodeLoader.getAdHoc(HealthCheck.class, checks.get(0).getCheck()).run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      HealthCheckMetaData destination = checks.get(1);
      assertThat(destination.getName()).isEqualTo("backups.destination." + active.getValueInteger("id"));
      assertThat(destination.getIntervalSeconds()).isEqualTo(86400);
      assertThat(destination.getTimeoutMs()).isEqualTo(30000);
      assertThat(destination.getCheckInstance().run(input()).getStatus()).isEqualTo(HealthStatus.UP);
      catalog.update(BackupsTables.PLAN, active.getValueInteger("id"), Map.of("isActive", false));
      assertThat(destination.getCheckInstance().run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      assertThat(registry.resolveAll(QContext.getQInstance())).hasSize(1);
      VerifyTestSupport.plan(Map.of("name", "New", "planKey", "new"));
      assertThat(registry.resolveAll(QContext.getQInstance())).hasSize(2);
   }



   /*******************************************************************************
    ** Carry over the indicator's empty, critical, ordinary and inactive cases.
    *******************************************************************************/
   @Test
   void testSlaUpDownAndDegraded() throws Exception
   {
      BackupsSlaHealthCheck check = new BackupsSlaHealthCheck();
      HealthCheckOutcome empty = check.run(input());
      assertThat(empty.getStatus()).isEqualTo(HealthStatus.UP);
      assertThat(empty.getDetails().getValues()).containsEntry("activePlans", 0);
      QRecord critical = VerifyTestSupport.plan(Map.of("critical", true));
      QRecord ordinary = VerifyTestSupport.plan(Map.of("name", "Other", "planKey", "other"));
      QRecord inactive = VerifyTestSupport.plan(Map.of("name", "Off", "planKey", "off", "isActive", false, "critical", true));
      setStatus(inactive, "LATE");
      setStatus(critical, "OK");
      setStatus(ordinary, "OK");
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UP);
      for(String status : List.of("LATE", "FAILING", "MISSING_IN_STORAGE"))
      {
         setStatus(ordinary, status);
         assertThat(check.run(input()).getStatus()).as(status).isEqualTo(HealthStatus.DEGRADED);
         setStatus(critical, status);
         HealthCheckOutcome down = check.run(input());
         assertThat(down.getStatus()).as(status).isEqualTo(HealthStatus.DOWN);
         assertThat(down.getDetails().getValues()).containsEntry("activePlans", 2).containsEntry("slaStatus." + status, 2);
         assertClean(down);
         setStatus(critical, "OK");
      }
   }



   /*******************************************************************************
    ** Unknown or unverified plans do not become a false UP or a critical DOWN.
    *******************************************************************************/
   @Test
   void testSlaUnknownAndMixedStates() throws Exception
   {
      QRecord plan = VerifyTestSupport.plan(Map.of("critical", true));
      BackupsSlaHealthCheck check = new BackupsSlaHealthCheck();
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      for(String status : List.of("UNVERIFIED", "UNKNOWN"))
      {
         setStatus(plan, status);
         assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      }
      QRecord other = VerifyTestSupport.plan(Map.of("name", "Other", "planKey", "other"));
      setStatus(other, "UNVERIFIED");
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      setStatus(other, "OK");
      assertThat(check.run(input()).getStatus()).isEqualTo(HealthStatus.UP);
   }



   /*******************************************************************************
    ** Preserve the old indicator's no-context behavior and existing sessions.
    *******************************************************************************/
   @Test
   void testWithoutContext() throws Exception
   {
      QRecord plan = VerifyTestSupport.plan(Map.of());
      QInstance instance = QContext.getQInstance();
      QSession session = QContext.getQSession();
      new BackupsSlaHealthCheck().run(input());
      assertThat(QContext.getQSession()).isSameAs(session);
      QContext.clear();
      try
      {
         HealthCheckInput input = new HealthCheckInput().withQInstance(instance).withDeadline(Instant.now().plusSeconds(5));
         assertThat(new BackupsSlaHealthCheck().run(input).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
         assertThat(QContext.getQInstance()).isNull();
         assertThat(new BackupsDestinationHealthChecks().supply(instance)).hasSize(1);
         assertThat(QContext.getQInstance()).isNull();
         assertThat(destination(plan).run(input).getStatus()).isEqualTo(HealthStatus.UP);
         assertThat(QContext.getQInstance()).isNull();
      }
      finally
      {
         QContext.init(instance, session);
      }
   }



   /*******************************************************************************
    ** Locked and unlocked plans only list/head; neither reads object contents.
    *******************************************************************************/
   @Test
   void testDestinationReadOnlyAndMissingManifest() throws Exception
   {
      for(String lockMode : List.of("NONE", "GOVERNANCE"))
      {
         BackupsRuntime.getConfig().withStorageResolver(alias -> BackupsTestUtils.BACKUPS);
         QRecord plan = VerifyTestSupport.plan(Map.of("name", lockMode, "planKey", lockMode.toLowerCase(), "objectLockMode", lockMode, "objectLockDays", 3, "pruneBucketAlias", "prune"));
         BackupsRuntime.getConfig().withStorageResolver(alias ->
         {
            assertThat(alias).as("never resolve prune credentials").isNotEqualTo("prune");
            return (storage);
         });
         Integer runId = catalog.insert(BackupsTables.RUN, Map.of("manifestKey", "manifest.json"));
         catalog.update(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of("lastSuccessRunId", runId));
         storage.putBytes("manifest.json", new byte[] { 1 });
         assertThat(destination(plan).run(input()).getStatus()).isEqualTo(HealthStatus.UP);
         storage.clear();
         assertThat(destination(plan).run(input()).getStatus()).isEqualTo(HealthStatus.DOWN);
      }
      assertThat(storage.lists).isEqualTo(4);
      assertThat(storage.heads).isEqualTo(4);
   }



   /*******************************************************************************
    ** A deleted plan is UNKNOWN and does not touch storage.
    *******************************************************************************/
   @Test
   void testDeletedPlan() throws Exception
   {
      QRecord plan = VerifyTestSupport.plan(Map.of());
      catalog.deleteByIds(BackupsTables.PLAN, List.of(plan.getValueInteger("id")));
      assertThat(destination(plan).run(input()).getStatus()).isEqualTo(HealthStatus.UNKNOWN);
      assertThat(storage.lists).isZero();
   }



   /*******************************************************************************
    ** No work starts with an expired deadline, including direct probe calls.
    *******************************************************************************/
   @Test
   void testExpiredDeadline() throws Exception
   {
      QRecord plan = VerifyTestSupport.plan(Map.of());
      HealthCheckInput expired = input().withDeadline(Instant.now().minusSeconds(1));
      assertThat(new BackupsSlaHealthCheck().run(expired).getStatus()).isEqualTo(HealthStatus.DOWN);
      assertThat(destination(plan).run(expired).getStatus()).isEqualTo(HealthStatus.DOWN);
      assertThat(DestinationProbe.probeReadOnly(plan, expired.getDeadline()).ok()).isFalse();
      assertThat(storage.lists).isZero();
      assertThat(storage.heads).isZero();
   }



   /*******************************************************************************
    ** Runner cancellation after list prevents the following manifest head.
    *******************************************************************************/
   @Test
   void testCancellationBetweenStorageCalls() throws Exception
   {
      QRecord plan = VerifyTestSupport.plan(Map.of());
      Integer runId = catalog.insert(BackupsTables.RUN, Map.of("manifestKey", "manifest.json"));
      catalog.update(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of("lastSuccessRunId", runId));
      storage.interruptAfterList = true;
      try
      {
         assertThat(destination(plan).run(input()).getStatus()).isEqualTo(HealthStatus.DOWN);
         assertThat(storage.lists).isEqualTo(1);
         assertThat(storage.heads).isZero();
      }
      finally
      {
         Thread.interrupted();
      }
   }



   /*******************************************************************************
    ** Poison configuration, persisted state and provider errors. sanitize is
    ** package-private in core; assert raw outcomes already contain no secrets.
    *******************************************************************************/
   @Test
   void testNoSecretsInSuccessAndFailureOutcomes() throws Exception
   {
      QRecord plan = VerifyTestSupport.plan(Map.of("name", SECRET, "storageBucketAlias", SECRET, "critical", true));
      Integer runId = catalog.insert(BackupsTables.RUN, Map.of("manifestKey", SECRET));
      catalog.update(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of("lastSuccessRunId", runId));
      storage.putBytes(SECRET, new byte[] { 1 });
      for(String status : List.of("OK", "LATE", "UNVERIFIED", SECRET))
      {
         setStatus(plan, status);
         assertClean(new BackupsSlaHealthCheck().run(input()));
         assertClean(destination(plan).run(input()));
      }
      storage.failList = true;
      HealthCheckOutcome failure = destination(plan).run(input());
      assertThat(failure.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertClean(failure);
      storage.failList = false;
      storage.failHead = true;
      failure = destination(plan).run(input());
      assertThat(failure.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertClean(failure);
      BackupsRuntime.getConfig().withStorageResolver(alias ->
      {
         throw (new IllegalStateException(SECRET));
      });
      failure = destination(plan).run(input());
      assertThat(failure.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertClean(failure);
      assertClean(new BackupsSlaHealthCheck().run(input()));
   }



   /*******************************************************************************
    ** Catalog failures are DOWN with fixed messages, even if metadata is poisoned.
    *******************************************************************************/
   @Test
   void testCatalogFailureDoesNotExposeSecrets()
   {
      QContext.getQInstance().getTable(BackupsTables.PLAN).setBackendName(SECRET);
      HealthCheckOutcome sla = new BackupsSlaHealthCheck().run(input());
      HealthCheckOutcome destination = new BackupsDestinationHealthCheck(1).run(input());
      assertThat(sla.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertThat(destination.getStatus()).isEqualTo(HealthStatus.DOWN);
      assertClean(sla);
      assertClean(destination);
   }



   /*******************************************************************************
    ** Update persisted SLA state without running the scheduled calculator.
    *******************************************************************************/
   private void setStatus(QRecord plan, String status) throws Exception
   {
      catalog.update(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of("slaStatus", status));
   }



   /*******************************************************************************
    ** Fresh bounded input for each call.
    *******************************************************************************/
   private HealthCheckInput input()
   {
      return (new HealthCheckInput().withQInstance(QContext.getQInstance()).withDeadline(Instant.now().plusSeconds(5)));
   }



   /*******************************************************************************
    ** Construct the same per-plan check as the supplier.
    *******************************************************************************/
   private BackupsDestinationHealthCheck destination(QRecord plan)
   {
      return (new BackupsDestinationHealthCheck(plan.getValueInteger("id")));
   }



   /*******************************************************************************
    ** Validate the raw public message and flattened detail representation.
    *******************************************************************************/
   private void assertClean(HealthCheckOutcome outcome)
   {
      assertThat(outcome.getMessage()).doesNotContain(SECRET, "\n", "\r");
      assertThat(outcome.getDetails().toJson()).doesNotContain(SECRET);
   }



   /*******************************************************************************
    ** Existing memory fake with read failures and assertions against side effects.
    *******************************************************************************/
   private static class ReadOnlyStorage extends InMemoryBackupStorage
   {
      private final String credential;
      private boolean failList;
      private boolean failHead;
      private boolean interruptAfterList;
      private int lists;
      private int heads;



      /*******************************************************************************
       ** Mimic the credential held by a host-provided storage adapter.
       *******************************************************************************/
      ReadOnlyStorage(String credential)
      {
         this.credential = credential;
      }



      /*******************************************************************************
       ** Record list calls and inject credential-bearing failures or cancellation.
       *******************************************************************************/
      @Override
      public List<StoredObjectInfo> list(String prefix)
      {
         lists++;
         if(failList)
         {
            throw (new IllegalStateException(credential));
         }
         if(interruptAfterList)
         {
            Thread.currentThread().interrupt();
         }
         return (super.list(prefix));
      }



      /*******************************************************************************
       ** Record head calls and inject credential-bearing failures.
       *******************************************************************************/
      @Override
      public Optional<StoredObjectInfo> head(String key)
      {
         heads++;
         if(failHead)
         {
            throw (new IllegalStateException(credential));
         }
         return (super.head(key));
      }



      /*******************************************************************************
       ** Health probes must not write, even to an unlocked bucket.
       *******************************************************************************/
      @Override
      public OutputStream openWrite(String key, WriteOptions options)
      {
         throw (new AssertionError("Health probe wrote an object"));
      }



      /*******************************************************************************
       ** Health probes must not fetch backup contents.
       *******************************************************************************/
      @Override
      public InputStream openRead(String key)
      {
         throw (new AssertionError("Health probe read object contents"));
      }



      /*******************************************************************************
       ** Health probes must not delete.
       *******************************************************************************/
      @Override
      public DeleteResult deletePermanent(String key)
      {
         throw (new AssertionError("Health probe deleted an object"));
      }



      /*******************************************************************************
       ** Capability diagnostics can be active probes; health must not invoke them.
       *******************************************************************************/
      @Override
      public Set<StorageCapability> capabilities()
      {
         throw (new AssertionError("Health probe requested capabilities"));
      }
   }
}
