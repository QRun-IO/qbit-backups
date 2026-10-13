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

package com.kingsrook.qbits.backups.storage.objectstorage;


import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.kingsrook.qbits.backups.BackupsRuntime;
import com.kingsrook.qbits.backups.BackupsTestUtils;
import com.kingsrook.qbits.backups.manifest.BackupManifest;
import com.kingsrook.qbits.backups.model.BackupsTables;
import com.kingsrook.qbits.backups.run.BackupCatalog;
import com.kingsrook.qbits.backups.run.BackupEngine;
import com.kingsrook.qbits.backups.run.BackupRunFactory;
import com.kingsrook.qbits.backups.run.BackupRunResult;
import com.kingsrook.qbits.backups.storage.BackupStorage;
import com.kingsrook.qbits.backups.storage.BackupStorageException;
import com.kingsrook.qbits.backups.storage.StoredObjectInfo;
import com.kingsrook.qbits.backups.storage.WriteOptions;
import com.kingsrook.qbits.objectstorage.ObjectStorageQBitConfig;
import com.kingsrook.qbits.objectstorage.api.ObjectStorage;
import com.kingsrook.qbits.objectstorage.config.BucketRole;
import com.kingsrook.qbits.objectstorage.config.ProviderType;
import com.kingsrook.qbits.objectstorage.config.StorageBucketConfig;
import com.kingsrook.qbits.objectstorage.config.StorageConnectionConfig;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** The backups qbit against a real S3-compatible store (MinIO, with object lock),
 ** through the object-storage qbit: the adapter contract, and a full backup run.
 *******************************************************************************/
class ObjectStorageBackupStorageMinioTest
{
   private static GenericContainer<?> minio;
   private static BackupStorage       storage;



   /*******************************************************************************
    **
    *******************************************************************************/
   @BeforeAll
   static void startMinio() throws Exception
   {
      org.junit.jupiter.api.Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable(), "Docker is not available: skipping the MinIO tests");
      minio = new GenericContainer<>("minio/minio:latest").withCommand("server", "/data").withExposedPorts(9000)
         .withEnv("MINIO_ROOT_USER", "minioadmin").withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
         .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
      try
      {
         minio.start();
      }
      catch(RuntimeException e)
      {
         // for example CI with a remote Docker that cannot pull the image: skip, never fail the build
         minio = null;
         org.junit.jupiter.api.Assumptions.assumeTrue(false, "MinIO could not be started: " + e.getMessage());
      }
      String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);

      try(S3Client s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
         .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("minioadmin", "minioadmin")))
         .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build())
      {
         s3.createBucket(b -> b.bucket("backups").objectLockEnabledForBucket(true));
      }

      ObjectStorage.configureStandalone(new ObjectStorageQBitConfig().withBackendName("memory")
         .withConnection(new StorageConnectionConfig().withName("minio").withProviderType(ProviderType.MINIO).withEndpoint(endpoint).withRegion("us-east-1")
            .withAccessKeyRef("${env.STORAGE_TEST_ACCESS_KEY}").withSecretKeyRef("${env.STORAGE_TEST_SECRET_KEY}"))
         .withBucket(new StorageBucketConfig().withAlias("backups").withConnection("minio").withBucketName("backups").withRole(BucketRole.BACKUP).withIndexed(false)));
      storage = ObjectStorageBackupStorage.systemResolver("test").apply("backups");
   }



   /*******************************************************************************
    **
    *******************************************************************************/
   @AfterAll
   static void stopMinio()
   {
      if(minio != null)
      {
         minio.stop();
      }
   }



   /*******************************************************************************
    ** Streamed write of unknown length, head, ranged-free read, list, copy, delete, presign.
    *******************************************************************************/
   @Test
   void testAdapterContract() throws Exception
   {
      byte[] big = new byte[20 * 1024 * 1024];
      new java.util.Random(7).nextBytes(big);
      try(OutputStream out = storage.openWrite("contract/big.bin", WriteOptions.create().withContentType("application/octet-stream").withMetadata("qqq-backup-run", "r1")))
      {
         out.write(big, 0, 5_000_000);
         out.write(big, 5_000_000, big.length - 5_000_000);
      }
      StoredObjectInfo head = storage.head("contract/big.bin").orElseThrow();
      assertThat(head.size()).isEqualTo(big.length);
      assertThat(storage.openRead("contract/big.bin").readAllBytes()).isEqualTo(big);
      assertThat(storage.head("contract/none")).isEmpty();
      assertThatThrownBy(() -> storage.openRead("contract/none")).isInstanceOf(BackupStorageException.class)
         .extracting(e -> ((BackupStorageException) e).getReason()).isEqualTo(BackupStorageException.Reason.NOT_FOUND);

      storage.copy("contract/big.bin", "contract/copy.bin");
      assertThat(storage.list("contract/")).extracting(StoredObjectInfo::key).containsExactly("contract/big.bin", "contract/copy.bin");
      assertThat(storage.presignGet("contract/copy.bin", Duration.ofMinutes(15), "x.bin")).startsWith("http");
      assertThat(storage.deletePermanent("contract/copy.bin")).isEqualTo(BackupStorage.DeleteResult.DELETED);
      assertThat(storage.deletePermanent("contract/copy.bin")).isEqualTo(BackupStorage.DeleteResult.NOT_FOUND);
   }



   /*******************************************************************************
    ** Object lock: a locked object is not deleted, and says so.
    *******************************************************************************/
   @Test
   void testObjectLockedObjectIsNotDeleted() throws Exception
   {
      try(OutputStream out = storage.openWrite("lock/a.txt", WriteOptions.create().withObjectLock("GOVERNANCE", Instant.now().plus(Duration.ofDays(1)))))
      {
         out.write("locked".getBytes(StandardCharsets.UTF_8));
      }
      assertThat(storage.head("lock/a.txt").orElseThrow().lockedUntil()).isNotNull();
      assertThat(storage.deletePermanent("lock/a.txt")).isEqualTo(BackupStorage.DeleteResult.LOCKED);
      assertThat(storage.head("lock/a.txt")).isPresent();
   }



   /*******************************************************************************
    ** A whole backup run through the storage qbit: manifest last, parts and files in
    ** the bucket, and the manifest is readable back.
    *******************************************************************************/
   @Test
   void testBackupRunAgainstMinio() throws Exception
   {
      BackupsTestUtils.init();
      BackupsTestUtils.loadBlog(5);
      BackupsRuntime.getConfig().withStorageResolver(alias -> "backups".equals(alias) ? storage : BackupsTestUtils.MEDIA);

      Integer id = new BackupCatalog().insert(BackupsTables.PLAN, Map.of("name", "Nightly", "planKey", "minio", "isActive", true, "scopeType", "INSTANCE",
         "storageBucketAlias", "backups", "slaHours", 26));
      QRecord plan = new BackupCatalog().get(BackupsTables.PLAN, id).orElseThrow();

      BackupRunResult result = new BackupEngine().execute(BackupRunFactory.forPlan(plan, "MANUAL"));
      assertThat(result.isUsable()).as(result.getErrorMessage()).isTrue();

      BackupManifest manifest = BackupManifest.read(storage.openRead(result.getManifestKey()));
      assertThat(manifest.table("blogPost").rowCount()).isEqualTo(5);
      List<StoredObjectInfo> objects = storage.list(result.getRunPrefix());
      assertThat(objects).extracting(StoredObjectInfo::key).contains(result.getRunPrefix() + "manifest.json", result.getRunPrefix() + "checksums.sha256");
      assertThat(storage.list("qqq-backups/testapp/_files/sha256/")).isNotEmpty();
   }
}
