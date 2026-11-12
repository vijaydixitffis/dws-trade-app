package com.dws.trade.query;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Outcome messages become MongoDB documents exactly once, however often they are delivered. */
@DataMongoTest
@Import(MongoConfig.class)
class ProjectorTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.mongo(registry);
    }

    @Autowired
    MongoTemplate mongo;

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private Projector projector;
    private History history;

    @BeforeEach
    void setUp() {
        mongo.dropCollection(EventDocument.class);
        mongo.dropCollection(RejectionDocument.class);
        history = new History(mongo);
        projector = new Projector(mapper, history, new LiveFeed());
    }

    private static String event(String id, String type, String at) {
        return """
                {"eventId":"%s","type":"%s","at":"%s","trade":{"tradeId":"T1","version":1,"counterPartyId":"CP-1",
                 "bookId":"B1","maturityDate":"2027-05-20","createdDate":"2026-11-12","expired":false}}"""
                .formatted(id, type, at);
    }

    private static String rejection(String id, String reason) {
        return """
                {"eventId":"%s","reason":"%s","message":"why","source":"PROCESSOR","at":"2026-11-12T10:00:00Z",
                 "trade":{"tradeId":"T2","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2027-05-20"}}"""
                .formatted(id, reason);
    }

    @Test
    void storesAnEventOnceEvenWhenItIsDeliveredTwice() throws Exception {
        projector.onEvent(event("trades.inbound-0-1", "ACCEPTED", "2026-11-12T10:00:00Z"));
        projector.onEvent(event("trades.inbound-0-1", "ACCEPTED", "2026-11-12T10:00:00Z"));

        List<EventDocument> events = history.latestEvents(10);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).id()).isEqualTo("trades.inbound-0-1");
        assertThat(events.get(0).trade().tradeId()).isEqualTo("T1");
        assertThat(events.get(0).trade().maturityDate()).hasToString("2027-05-20");
    }

    @Test
    void returnsNewestEventsFirstUpToTheLimit() throws Exception {
        projector.onEvent(event("e1", "ACCEPTED", "2026-11-12T10:00:00Z"));
        projector.onEvent(event("e2", "REPLACED", "2026-11-12T10:00:02Z"));
        projector.onEvent(event("e3", "EXPIRED", "2026-11-12T10:00:01Z"));

        assertThat(history.latestEvents(2)).extracting(EventDocument::id).containsExactly("e2", "e3");
    }

    @Test
    void storesRejectionsOnceWithTheirReasonAndCountsThemByReason() throws Exception {
        projector.onRejection(rejection("r1", "LOWER_VERSION"));
        projector.onRejection(rejection("r1", "LOWER_VERSION"));
        projector.onRejection(rejection("r2", "MATURITY_DATE_PASSED"));

        assertThat(history.latestRejections(10)).hasSize(2);
        assertThat(history.countRejections("LOWER_VERSION")).isEqualTo(1);
        assertThat(history.countRejections("MATURITY_DATE_PASSED")).isEqualTo(1);
        assertThat(history.latestRejections(10).get(0).message()).isEqualTo("why");
    }

    @Test
    void theFirstDeliveryOfAnEventWinsWhenARedeliveryCarriesAnotherType() throws Exception {
        // After a crash the processor can reprocess a record and describe it differently
        // (REPLACED instead of ACCEPTED). The history keeps what was said first.
        projector.onEvent(event("trades.inbound-0-1", "ACCEPTED", "2026-11-12T10:00:00Z"));
        projector.onEvent(event("trades.inbound-0-1", "REPLACED", "2026-11-12T10:00:05Z"));

        List<EventDocument> events = history.latestEvents(10);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type().name()).isEqualTo("ACCEPTED");
    }

    @Test
    void theFirstDeliveryOfARejectionWins() throws Exception {
        projector.onRejection(rejection("r1", "LOWER_VERSION"));
        projector.onRejection(rejection("r1", "MATURITY_DATE_PASSED"));

        assertThat(history.latestRejections(10)).singleElement()
                .satisfies(r -> assertThat(r.reason().name()).isEqualTo("LOWER_VERSION"));
    }

    @Test
    void storesDatesAsPlainIsoStringsSoTheyDoNotDependOnTheServerTimeZone() throws Exception {
        projector.onEvent(event("trades.inbound-0-9", "ACCEPTED", "2026-11-12T10:00:00Z"));

        org.bson.Document raw = mongo.getCollection("trade_events").find().first();
        org.bson.Document trade = (org.bson.Document) raw.get("trade");
        assertThat(trade.get("maturityDate")).isEqualTo("2027-05-20");
        assertThat(trade.get("createdDate")).isEqualTo("2026-11-12");
    }
}
