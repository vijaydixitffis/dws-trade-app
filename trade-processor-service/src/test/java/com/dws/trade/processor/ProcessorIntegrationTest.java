package com.dws.trade.processor;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Inbound topic to trade store to outcome topics, with real Kafka and PostgreSQL. */
@SpringBootTest(properties = {
        "trade.expiry.cron=-",
        "trade.retry.initial-interval-ms=50",
        "trade.retry.max-interval-ms=500",
        "trade.business-zone=UTC"})
class ProcessorIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
        Containers.kafka(registry);
        // A statement that cannot finish within two seconds fails instead of hanging, so a
        // paused database shows up as errors that the retry policy has to ride out.
        registry.add("spring.datasource.url", () -> Containers.POSTGRES.getJdbcUrl() + "&socketTimeout=2");
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

    private void pollMore() {
        outcomes.poll(Duration.ofMillis(200)).forEach(received::add);
    }

    private List<ConsumerRecord<String, String>> recordsFor(String topic, String key) {
        return received.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).toList();
    }

    /** Polls until the n-th record for the key arrives on the topic, and returns it. */
    private ConsumerRecord<String, String> awaitRecord(String topic, String key, int occurrence) {
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            pollMore();
            return recordsFor(topic, key).size() >= occurrence;
        });
        return recordsFor(topic, key).get(occurrence - 1);
    }

    private JsonNode awaitOutcome(String topic, String key, int occurrence) {
        return mapper.readTree(awaitRecord(topic, key, occurrence).value());
    }

    private void assertNothingOn(String topic, String key) {
        // Let anything still in flight arrive before asserting an absence.
        for (int i = 0; i < 10; i++) {
            pollMore();
        }
        assertThat(recordsFor(topic, key)).isEmpty();
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
    void unreadableMessagesGoToTheDeadLetterTopicAndConsumptionContinues() {
        String bad = "IT-BAD-" + System.nanoTime();
        String good = "IT-GOOD-" + System.nanoTime();
        send(bad, "this is not json");
        send(good, trade(good, 1, "B1", future));

        assertThat(awaitRecord(Topics.INBOUND_DLT, bad, 1).value()).isEqualTo("this is not json");
        assertThat(awaitOutcome(Topics.EVENTS, good, 1).get("type").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void readableButInvalidTradesAreRejectedWithAReasonNotDeadLettered() {
        String incomplete = "IT-INC-" + System.nanoTime();
        String tooLong = "IT-LONG-" + "X".repeat(70);
        String good = "IT-GOOD-" + System.nanoTime();
        send(incomplete, "{\"tradeId\":\"" + incomplete + "\",\"version\":1}");
        send(tooLong, trade(tooLong, 1, "B1", future));
        send(good, trade(good, 1, "B1", future));

        JsonNode first = awaitOutcome(Topics.REJECTED, incomplete, 1);
        assertThat(first.get("reason").asText()).isEqualTo("INVALID_TRADE");
        assertThat(first.get("source").asText()).isEqualTo("PROCESSOR");
        assertThat(first.get("message").asText()).contains("counterPartyId");
        JsonNode second = awaitOutcome(Topics.REJECTED, tooLong, 1);
        assertThat(second.get("reason").asText()).isEqualTo("INVALID_TRADE");
        assertThat(second.get("message").asText()).contains("tradeId");

        assertThat(awaitOutcome(Topics.EVENTS, good, 1).get("type").asText()).isEqualTo("ACCEPTED");
        assertNothingOn(Topics.INBOUND_DLT, incomplete);
        assertNothingOn(Topics.INBOUND_DLT, tooLong);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade WHERE trade_id = ?", Integer.class, tooLong)).isZero();
    }

    @Test
    void aShortDatabaseOutageDelaysTradesButNeverDeadLettersThem() throws Exception {
        String before = "IT-PRE-" + System.nanoTime();
        String during = "IT-OUT-" + System.nanoTime();
        send(before, trade(before, 1, "B1", future));
        awaitOutcome(Topics.EVENTS, before, 1);

        Containers.pause(Containers.POSTGRES);
        try {
            send(during, trade(during, 1, "B1", future));
            Thread.sleep(5_000);
        } finally {
            Containers.resume(Containers.POSTGRES);
        }

        assertThat(awaitOutcome(Topics.EVENTS, during, 1).get("type").asText()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade WHERE trade_id = ?", Integer.class, during))
                .isEqualTo(1);
        assertNothingOn(Topics.INBOUND_DLT, during);
    }
}
