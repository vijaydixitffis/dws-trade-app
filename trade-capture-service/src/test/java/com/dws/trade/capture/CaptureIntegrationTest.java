package com.dws.trade.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dws.trade.event.Topics;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/** REST in, Kafka out, against a real broker. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaptureIntegrationTest {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        KAFKA.start();
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> post(String id, LocalDate maturity) {
        return rest.postForEntity("/api/v1/trades",
                Map.of("tradeId", id, "version", 1, "counterPartyId", "CP-1", "bookId", "B1",
                        "maturityDate", maturity.toString()),
                String.class);
    }

    @Test
    void validTradeLandsOnInboundTopicKeyedByTradeIdAndPastMaturityOnRejectedTopic() {
        assertThat(post("CAP-1", LocalDate.now().plusYears(1)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post("CAP-OLD", LocalDate.now().minusDays(1)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "capture-it",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(Topics.INBOUND, Topics.REJECTED));
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                return records.size() >= 2;
            });
        }

        assertThat(records).filteredOn(r -> r.topic().equals(Topics.INBOUND)).singleElement()
                .satisfies(r -> {
                    assertThat(r.key()).isEqualTo("CAP-1");
                    assertThat(r.value()).contains("\"tradeId\":\"CAP-1\"").contains("\"version\":1");
                });
        assertThat(records).filteredOn(r -> r.topic().equals(Topics.REJECTED)).singleElement()
                .satisfies(r -> {
                    assertThat(r.key()).isEqualTo("CAP-OLD");
                    assertThat(r.value()).contains("MATURITY_DATE_PASSED").contains("\"source\":\"CAPTURE\"");
                });
    }
}
