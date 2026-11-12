package com.dws.trade.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.dws.trade.event.Topics;
import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Outcome topics to MongoDB to REST and the live feed, with real Kafka, PostgreSQL and MongoDB. */
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueryIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
        Containers.mongo(registry);
        Containers.kafka(registry);
    }

    @Autowired
    TestRestTemplate rest;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;
    @LocalServerPort
    int port;

    private TestRestTemplate api() {
        return rest.withBasicAuth("admin", "admin@2026!");
    }

    private static String event(String eventId, String tradeId) {
        return """
                {"eventId":"%s","type":"ACCEPTED","at":"2026-11-12T10:00:00Z","trade":{"tradeId":"%s","version":1,
                 "counterPartyId":"CP-1","bookId":"B1","maturityDate":"2027-05-20","createdDate":"2026-11-12",
                 "expired":false}}""".formatted(eventId, tradeId);
    }

    @Test
    void servesTradesFromTheStoreInSequence() {
        jdbc.update("TRUNCATE trade, trade_latest");
        LocalDate maturity = LocalDate.now().plusYears(1);
        jdbc.update("INSERT INTO trade VALUES ('Q2', 1, 'CP-1', 'B1', ?, ?, 'N')", maturity, LocalDate.now());
        jdbc.update("INSERT INTO trade VALUES ('Q2', 2, 'CP-2', 'B1', ?, ?, 'N')", maturity, LocalDate.now());
        jdbc.update("INSERT INTO trade VALUES ('Q1', 1, 'CP-1', 'B1', ?, ?, 'Y')", maturity, LocalDate.now());

        JsonNode page = api().getForObject("/api/v1/trades", JsonNode.class);

        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("items")).extracting(t -> t.get("tradeId").asText() + ":" + t.get("version").asInt())
                .containsExactly("Q1:1", "Q2:2", "Q2:1");
        assertThat(page.get("items").get(0).get("expired").asBoolean()).isTrue();
        assertThat(page.get("items").get(0).get("maturityDate").asText()).isEqualTo(maturity.toString());
    }

    @Test
    void projectsEventsAndRejectionsFromKafkaAndServesThemWithASummary() {
        String id = "QI-" + System.nanoTime();
        kafka.send(Topics.EVENTS, id, event("ev-" + id, id));
        kafka.send(Topics.REJECTED, id, """
                {"eventId":"rj-%s","reason":"LOWER_VERSION","message":"too low","source":"PROCESSOR",
                 "at":"2026-11-12T10:00:01Z","trade":{"tradeId":"%s","version":1,"counterPartyId":"CP-1",
                 "bookId":"B1","maturityDate":"2027-05-20"}}""".formatted(id, id));

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertThat(api().getForObject("/api/v1/events?limit=500", JsonNode.class))
                    .anyMatch(e -> e.get("id").asText().equals("ev-" + id));
            assertThat(api().getForObject("/api/v1/rejections?limit=500", JsonNode.class))
                    .anyMatch(r -> r.get("id").asText().equals("rj-" + id)
                            && r.get("reason").asText().equals("LOWER_VERSION"));
        });

        JsonNode summary = api().getForObject("/api/v1/summary", JsonNode.class);
        assertThat(summary.get("rejectedLowerVersion").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(summary.get("accepted").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(summary.has("trades")).isTrue();
        assertThat(summary.has("businessDate")).isTrue();
    }

    @Test
    void pushesNewEventsToConnectedDashboards() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        HttpClient client = HttpClient.newHttpClient();
        client.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/stream"))
                                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                                        .encodeToString("admin:admin@2026!".getBytes())).build(),
                        HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(lines::add));
        // The feed greets each subscriber, which tells us the connection is open.
        await().atMost(Duration.ofSeconds(30)).until(() -> lines.stream().anyMatch(l -> l.contains("connected")));

        String id = "LIVE-" + System.nanoTime();
        kafka.send(Topics.EVENTS, id, event("ev-" + id, id));

        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> lines.stream().anyMatch(l -> l.contains(id)));
            assertThat(lines).anyMatch(l -> l.equals("event:trade-event"));
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    void refusesReadsAndTheLiveFeedWithoutCredentials() {
        assertThat(rest.getForEntity("/api/v1/trades", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/summary", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/stream", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
