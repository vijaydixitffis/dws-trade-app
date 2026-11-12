package com.dws.trade.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dws.trade.event.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Inbound topic to trade store to outcome topics, with real Kafka and PostgreSQL. */
@SpringBootTest(properties = {
        "trade.expiry.cron=-",
        "trade.retry.interval-ms=50",
        "trade.business-zone=UTC"})
class ProcessorIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
        Containers.kafka(registry);
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    private KafkaConsumer<String, String> outcomes;
    private final List<ConsumerRecord<String, String>> received = new ArrayList<>();
    private final LocalDate future = LocalDate.now().plusYears(1);

    @BeforeEach
    void subscribe() {
        outcomes = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        outcomes.subscribe(List.of(Topics.EVENTS, Topics.REJECTED, Topics.INBOUND_DLT));
    }

    @AfterEach
    void close() {
        outcomes.close();
    }

    private void send(String tradeId, String json) {
        kafka.send(Topics.INBOUND, tradeId, json);
    }

    private String trade(String id, int version, String book, LocalDate maturity) {
        return """
                {"tradeId":"%s","version":%d,"counterPartyId":"CP-1","bookId":"%s","maturityDate":"%s"}"""
                .formatted(id, version, book, maturity);
    }

    /** Polls until an outcome for the key arrives on the topic, and returns its JSON. */
    private JsonNode awaitOutcome(String topic, String key, int occurrence) {
        List<ConsumerRecord<String, String>> matches = new ArrayList<>();
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            outcomes.poll(Duration.ofMillis(200)).forEach(received::add);
            matches.clear();
            received.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).forEach(matches::add);
            return matches.size() >= occurrence;
        });
        try {
            return mapper.readTree(matches.get(occurrence - 1).value());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void acceptsReplacesAndRejectsTradesInArrivalOrder() {
        String id = "IT-" + System.nanoTime();
        send(id, trade(id, 1, "B1", future));
        send(id, trade(id, 2, "B1", future));
        send(id, trade(id, 2, "B2", future));
        send(id, trade(id, 1, "B3", future));

        assertThat(awaitOutcome(Topics.EVENTS, id, 1).get("type").asText()).isEqualTo("ACCEPTED");
        assertThat(awaitOutcome(Topics.EVENTS, id, 2).get("type").asText()).isEqualTo("ACCEPTED");
        JsonNode replaced = awaitOutcome(Topics.EVENTS, id, 3);
        assertThat(replaced.get("type").asText()).isEqualTo("REPLACED");
        assertThat(replaced.get("trade").get("bookId").asText()).isEqualTo("B2");
        assertThat(replaced.get("eventId").asText()).startsWith(Topics.INBOUND + "-");

        JsonNode rejection = awaitOutcome(Topics.REJECTED, id, 1);
        assertThat(rejection.get("reason").asText()).isEqualTo("LOWER_VERSION");
        assertThat(rejection.get("source").asText()).isEqualTo("PROCESSOR");

        assertThat(jdbc.queryForList("SELECT version || ':' || book_id FROM trade WHERE trade_id = ? ORDER BY version",
                String.class, id)).containsExactly("1:B1", "2:B2");
    }

    @Test
    void rejectsTradeWithPastMaturityDateSentStraightToTheTopic() {
        String id = "IT-OLD-" + System.nanoTime();
        send(id, trade(id, 1, "B1", LocalDate.now().minusDays(2)));

        assertThat(awaitOutcome(Topics.REJECTED, id, 1).get("reason").asText()).isEqualTo("MATURITY_DATE_PASSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade WHERE trade_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void sendsUnreadableAndIncompleteMessagesToTheDeadLetterTopicAndKeepsConsuming() {
        String bad = "IT-BAD-" + System.nanoTime();
        String incomplete = "IT-INC-" + System.nanoTime();
        String good = "IT-GOOD-" + System.nanoTime();
        send(bad, "this is not json");
        send(incomplete, "{\"tradeId\":\"" + incomplete + "\",\"version\":1}");
        send(good, trade(good, 1, "B1", future));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            outcomes.poll(Duration.ofMillis(200)).forEach(received::add);
            assertThat(received).filteredOn(r -> r.topic().equals(Topics.INBOUND_DLT))
                    .extracting(ConsumerRecord::key).contains(bad, incomplete);
        });
        assertThat(awaitOutcome(Topics.EVENTS, good, 1).get("type").asText()).isEqualTo("ACCEPTED");
    }
}
