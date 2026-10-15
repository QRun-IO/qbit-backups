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


import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import com.kingsrook.qbits.backups.metrics.BackupsMetrics;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qqq.backend.core.cluster.ClusterBus;
import com.kingsrook.qqq.backend.core.cluster.ClusterBusInterface;
import com.kingsrook.qqq.backend.core.cluster.ClusterBusMetaData;
import com.kingsrook.qqq.backend.core.cluster.ClusterCacheAudit;
import com.kingsrook.qqq.backend.core.cluster.ClusterEvent;
import com.kingsrook.qqq.backend.core.cluster.QNodeRoles;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;


/*******************************************************************************
 ** The Backups caches are correct on several nodes: every static cache is
 ** registered with ClusterCaches, and the cached metrics are cleared on every
 ** node when a plan changes.
 *******************************************************************************/
class BackupsClusterCachesTest
{
   /** the "wire": events every node's transport sees */
   static final List<Consumer<ClusterEvent>> WIRE = new CopyOnWriteArrayList<>();



   /*******************************************************************************
    ** A transport over the static WIRE, so a "remote" publish reaches us.
    *******************************************************************************/
   public static class WireBus implements ClusterBusInterface
   {
      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public void publish(ClusterEvent event)
      {
         WIRE.forEach(listener -> listener.accept(event));
      }



      /*******************************************************************************
       **
       *******************************************************************************/
      @Override
      public AutoCloseable subscribe(Consumer<ClusterEvent> listener)
      {
         WIRE.add(listener);
         return (() -> WIRE.remove(listener));
      }
   }



   /*******************************************************************************
    ** A backed-up test instance, connected to the wire as node-a.
    *******************************************************************************/
   @BeforeEach
   void setUp() throws Exception
   {
      VerifyTestSupport.setUp(1);
      QInstance instance = QContext.getQInstance();
      instance.withSupplementalMetaData(new ClusterBusMetaData().withBus(new QCodeReference(WireBus.class)));
      QNodeRoles.set(new QNodeRoles.Settings(EnumSet.allOf(QNodeRoles.Role.class), true, true, true, null, "node-a"));
      ClusterBus.connect(instance);
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterEach
   void afterEach()
   {
      ClusterBus.close(QContext.getQInstance());
      WIRE.clear();
      QNodeRoles.set(null);
   }



   /*******************************************************************************
    ** Every static Memoization in the QBit is registered (shared or node-local).
    *******************************************************************************/
   @Test
   void testNoUnregisteredStaticCaches() throws Exception
   {
      assertThat(ClusterCacheAudit.unregisteredStaticCaches("com.kingsrook.qbits.backups")).isEmpty();
   }



   /*******************************************************************************
    ** Another node's invalidation clears this node's cached metrics.
    *******************************************************************************/
   @Test
   void testRemoteInvalidationClearsMetricsCache() throws Exception
   {
      String cached = BackupsMetrics.renderCached();
      assertThat(BackupsMetrics.renderCached()).isSameAs(cached);

      ClusterEvent remote = new ClusterEvent(ClusterEvent.TYPE_MEMO_INVALIDATE, BackupsMetrics.CACHE_NAME, null);
      remote.setOriginNodeId("node-b");
      WIRE.forEach(listener -> listener.accept(remote));

      assertThat(BackupsMetrics.renderCached()).isNotSameAs(cached);
   }



   /*******************************************************************************
    ** A plan edit here clears the metrics here and tells the other nodes.
    *******************************************************************************/
   @Test
   void testPlanEditPublishesMetricsInvalidation() throws Exception
   {
      QRecord plan = VerifyTestSupport.plan(Map.of());
      String  cached = BackupsMetrics.renderCached();

      List<ClusterEvent> sent = new CopyOnWriteArrayList<>();
      WIRE.add(sent::add);

      new BackupCatalog().update(BackupsTables.PLAN, plan.getValueInteger("id"), Map.of("slaHours", 48));

      assertThat(BackupsMetrics.renderCached()).isNotSameAs(cached);
      assertThat(sent).anySatisfy(event ->
      {
         assertThat(event.getType()).isEqualTo(ClusterEvent.TYPE_MEMO_INVALIDATE);
         assertThat(event.getName()).isEqualTo(BackupsMetrics.CACHE_NAME);
         assertThat(event.getOriginNodeId()).isEqualTo("node-a");
      });
   }
}
