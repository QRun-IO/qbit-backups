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


import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.backend.core.model.metadata.qbits.QBitConfigValidationException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Unit test for BackupsQBitProducer
 *******************************************************************************/
class BackupsQBitProducerTest
{

   /*******************************************************************************
    ** A configured qbit registers itself in the instance.
    *******************************************************************************/
   @Test
   void testProduce() throws Exception
   {
      QInstance           qInstance = new QInstance();
      BackupsQBitConfig config    = new BackupsQBitConfig().withBackendName("rdbms");
      BackupsQBitProducer producer  = new BackupsQBitProducer().withQBitConfig(config).withNamespace("backups");

      assertThat(producer.isEnabled()).isTrue();
      assertThat(producer.getQBitConfig()).isSameAs(config);
      assertThat(producer.getNamespace()).isEqualTo("backups");
      assertThat(config.getDefaultBackendNameForTables()).isEqualTo("rdbms");

      producer.produce(qInstance);
      assertThat(qInstance.getQBits()).hasSize(1);
      assertThat(qInstance.getQBits().values().iterator().next().getArtifactId()).isEqualTo("qbit-backups");
   }



   /*******************************************************************************
    ** Without a config the producer is disabled; without a backend it fails.
    *******************************************************************************/
   @Test
   void testDisabledAndInvalid()
   {
      assertThat(new BackupsQBitProducer().isEnabled()).isFalse();

      BackupsQBitConfig config = new BackupsQBitConfig();
      config.setBackendName(null);
      assertThat(config.getBackendName()).isNull();
      assertThatThrownBy(() -> new BackupsQBitProducer().withQBitConfig(config).produce(new QInstance()))
         .isInstanceOf(QBitConfigValidationException.class);
   }



   /*******************************************************************************
    ** Names follow the owner rule: lowercase snake_case with the full qbit name.
    *******************************************************************************/
   @Test
   void testNaming()
   {
      assertThat(BackupsQBitConfig.TABLE_NAME_PREFIX).isEqualTo("backups_").matches("^[a-z][a-z0-9_]*_$");
      assertThat(BackupsQBitConfig.PERMISSION_BASE_NAME).isEqualTo("backups");
   }



   /*******************************************************************************
    ** Long operations always enqueue, with no unsafe automatic external replay.
    *******************************************************************************/
   @Test
   void testDurableProcesses() throws Exception
   {
      BackupsTestUtils.init();
      for(String name : java.util.List.of("backupRunPlan", "backupRunScheduledPlan", "backupResumeRun", "backupRestore", "backupRestoreTable", "backupVerify"))
      {
         var process = com.kingsrook.qqq.backend.core.context.QContext.getQInstance().getProcess(name);
         assertThat(process).as(name).isNotNull();
         var durability = com.kingsrook.qqq.backend.core.jobs.DurableProcessMetaData.of(process);
         assertThat(durability).as(name).isNotNull();
         assertThat(durability.getAlwaysDurable()).isTrue();
         assertThat(durability.getEligible()).isTrue();
         assertThat(durability.getIdempotency()).isEqualTo(com.kingsrook.qqq.backend.core.jobs.model.JobIdempotency.NONE);
         assertThat(durability.getChunkSize()).isEqualTo(1);
      }
   }

}
