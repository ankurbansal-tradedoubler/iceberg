/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.tabular.iceberg.connect;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.aws.AwsProperties;
import org.apache.iceberg.aws.s3.S3FileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@SuppressWarnings("rawtypes")
public class IntegrationTestBase {

  protected static Network network;
  protected static KafkaContainer kafka;
  protected static KafkaConnectContainer kafkaConnect;
  protected static GenericContainer restCatalog;
  protected static GenericContainer minio;

  protected static S3Client s3;
  protected static RESTCatalog catalog;
  protected static KafkaProducer<String, String> producer;
  protected static Admin admin;

  private static final String LOCAL_INSTALL_DIR = "build/install";
  private static final String KC_PLUGIN_DIR = "/test/kafka-connect";

  private static final String BUCKET = "bucket";
  protected static final String AWS_ACCESS_KEY = "minioadmin";
  protected static final String AWS_SECRET_KEY = "minioadmin";
  protected static final String AWS_REGION = "us-east-1";

  private static final String MINIO_IMAGE = "minio/minio";
  private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.4.0";
  private static final String CONNECT_IMAGE = "confluentinc/cp-kafka-connect:7.4.0";
  private static final String REST_CATALOG_IMAGE = "tabulario/iceberg-rest:0.4.0";

  @BeforeAll
  public static void setupAll() {
    network = Network.newNetwork();

    minio =
        new GenericContainer(DockerImageName.parse(MINIO_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("minio")
            .withExposedPorts(9000)
            .withCommand("server /data")
            .waitingFor(new HttpWaitStrategy().forPort(9000).forPath("/minio/health/ready"));

    restCatalog =
        new GenericContainer(DockerImageName.parse(REST_CATALOG_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("iceberg")
            .dependsOn(minio)
            .withExposedPorts(8181)
            .withEnv("CATALOG_WAREHOUSE", "s3://" + BUCKET + "/warehouse")
            .withEnv("CATALOG_IO__IMPL", S3FileIO.class.getName())
            .withEnv("CATALOG_S3_ENDPOINT", "http://minio:9000")
            .withEnv("CATALOG_S3_ACCESS__KEY__ID", AWS_ACCESS_KEY)
            .withEnv("CATALOG_S3_SECRET__ACCESS__KEY", AWS_SECRET_KEY)
            .withEnv("CATALOG_S3_PATH__STYLE__ACCESS", "true")
            .withEnv("AWS_REGION", AWS_REGION);

    kafka = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE)).withNetwork(network);

    kafkaConnect =
        new KafkaConnectContainer(DockerImageName.parse(CONNECT_IMAGE))
            .withNetwork(network)
            .dependsOn(restCatalog, kafka)
            .withFileSystemBind(LOCAL_INSTALL_DIR, KC_PLUGIN_DIR)
            .withEnv("CONNECT_PLUGIN_PATH", KC_PLUGIN_DIR)
            .withEnv("CONNECT_BOOTSTRAP_SERVERS", kafka.getNetworkAliases().get(0) + ":9092")
            .withEnv("CONNECT_OFFSET_FLUSH_INTERVAL_MS", "500");

    Startables.deepStart(Stream.of(minio, restCatalog, kafka, kafkaConnect)).join();

    s3 = initLocalS3Client();
    s3.createBucket(req -> req.bucket(BUCKET));
    catalog = initLocalCatalog();
    producer = initLocalProducer();
    admin = initLocalAdmin();
  }

  @AfterAll
  public static void teardownAll() {
    kafkaConnect.close();
    try {
      catalog.close();
    } catch (IOException e) {
      // NO-OP
    }
    producer.close();
    admin.close();
    kafka.close();
    restCatalog.close();
    s3.close();
    minio.close();
    network.close();
  }

  protected void createTopic(String topicName, int partitions) {
    try {
      admin
          .createTopics(ImmutableList.of(new NewTopic(topicName, partitions, (short) 1)))
          .all()
          .get(10, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  protected void deleteTopic(String topicName) {
    try {
      admin.deleteTopics(ImmutableList.of(topicName)).all().get(10, TimeUnit.SECONDS);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  private static S3Client initLocalS3Client() {
    try {
      return S3Client.builder()
          .endpointOverride(new URI("http://localhost:" + minio.getMappedPort(9000)))
          .region(Region.of(AWS_REGION))
          .forcePathStyle(true)
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create(AWS_ACCESS_KEY, AWS_SECRET_KEY)))
          .build();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private static RESTCatalog initLocalCatalog() {
    String localCatalogUri = "http://localhost:" + restCatalog.getMappedPort(8181);
    RESTCatalog result = new RESTCatalog();
    result.initialize(
        "local",
        ImmutableMap.<String, String>builder()
            .put(CatalogProperties.URI, localCatalogUri)
            .put(CatalogProperties.FILE_IO_IMPL, S3FileIO.class.getName())
            .put(AwsProperties.S3FILEIO_ENDPOINT, "http://localhost:" + minio.getMappedPort(9000))
            .put(AwsProperties.S3FILEIO_ACCESS_KEY_ID, AWS_ACCESS_KEY)
            .put(AwsProperties.S3FILEIO_SECRET_ACCESS_KEY, AWS_SECRET_KEY)
            .put(AwsProperties.S3FILEIO_PATH_STYLE_ACCESS, "true")
            .put(AwsProperties.HTTP_CLIENT_TYPE, AwsProperties.HTTP_CLIENT_TYPE_APACHE)
            .build());
    return result;
  }

  private static KafkaProducer<String, String> initLocalProducer() {
    return new KafkaProducer<>(
        ImmutableMap.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            kafka.getBootstrapServers(),
            ProducerConfig.CLIENT_ID_CONFIG,
            UUID.randomUUID().toString()),
        new StringSerializer(),
        new StringSerializer());
  }

  private static Admin initLocalAdmin() {
    return Admin.create(
        ImmutableMap.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
  }
}
