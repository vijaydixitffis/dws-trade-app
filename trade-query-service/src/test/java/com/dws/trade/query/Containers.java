package com.dws.trade.query;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/** Containers shared by every test class in the module; started once per JVM. */
public final class Containers {

    public static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    public static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.2");
    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    private Containers() {
    }

    public static void postgres(DynamicPropertyRegistry registry) {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    public static void mongo(DynamicPropertyRegistry registry) {
        if (!MONGO.isRunning()) {
            MONGO.start();
        }
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
    }

    public static void kafka(DynamicPropertyRegistry registry) {
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    public static void pause(GenericContainer<?> container) {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    public static void resume(GenericContainer<?> container) {
        container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    }
}
