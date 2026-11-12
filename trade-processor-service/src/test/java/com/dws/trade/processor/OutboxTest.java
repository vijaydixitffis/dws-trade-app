package com.dws.trade.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dws.trade.event.Topics;
import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeEventType;
import com.dws.trade.model.StoredTrade;
import com.dws.trade.processor.outbox.OutboundMessage;
import com.dws.trade.processor.outbox.OutboxRelay;
import com.dws.trade.processor.outbox.OutboxWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Events are written in the business transaction and published afterwards, in order, exactly once. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxTest {

    private static final LocalDate DATE = LocalDate.of(2027, 5, 20);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final OutcomePublisher publisher = mock(OutcomePublisher.class);
    private OutboxWriter writer;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE outbox");
        writer = new OutboxWriter(jdbc, JsonMapper.builder().findAndAddModules().build());
        relay = new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), publisher, 100);
    }

    private static TradeEvent event(String eventId, String tradeId) {
        return new TradeEvent(eventId, TradeEventType.ACCEPTED,
                new StoredTrade(tradeId, 1, "CP-1", "B1", DATE, DATE, false), Instant.parse("2026-11-12T10:00:00Z"));
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class);
    }

    @Test
    void writesOneRowPerEventAndIgnoresARepeatOfTheSameEventId() {
        writer.write(event("e1", "T1"));
        writer.write(event("e1", "T1"));
        writer.write(event("e2", "T2"));

        assertThat(rows()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT message_key FROM outbox WHERE event_id = 'e2'", String.class))
                .isEqualTo("T2");
    }

    @Test
    void publishesInTheOrderWrittenAndDeletesWhatItPublished() {
        writer.write(event("e1", "T1"));
        writer.write(event("e2", "T2"));
        writer.write(event("e3", "T1"));

        int published = relay.relay();

        assertThat(published).isEqualTo(3);
        ArgumentCaptor<List<OutboundMessage>> sent = ArgumentCaptor.captor();
        verify(publisher).sendAll(sent.capture());
        assertThat(sent.getValue()).extracting(OutboundMessage::key).containsExactly("T1", "T2", "T1");
        assertThat(sent.getValue()).allSatisfy(m -> assertThat(m.topic()).isEqualTo(Topics.EVENTS));
        assertThat(sent.getValue().get(0).payload()).contains("\"eventId\":\"e1\"").contains("\"type\":\"ACCEPTED\"");
        assertThat(rows()).isZero();
    }

    @Test
    void doesNothingWhenThereIsNothingToPublish() {
        assertThat(relay.relay()).isZero();

        verify(publisher, never()).sendAll(anyList());
    }

    @Test
    void keepsTheRowsWhenPublishingFailsAndPublishesThemOnTheNextRun() {
        writer.write(event("e1", "T1"));
        writer.write(event("e2", "T2"));
        doThrow(new IllegalStateException("broker down")).when(publisher).sendAll(anyList());

        assertThatThrownBy(() -> relay.relay()).hasMessage("broker down");
        assertThat(rows()).isEqualTo(2);

        org.mockito.Mockito.reset(publisher);
        assertThat(relay.relay()).isEqualTo(2);
        assertThat(rows()).isZero();
    }

    @Test
    void sendsLargeBacklogsInBatchesOfTheConfiguredSize() {
        for (int i = 0; i < 25; i++) {
            writer.write(event("e" + i, "T" + i));
        }
        OutboxRelay small = new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), publisher, 10);

        assertThat(small.relay()).isEqualTo(10);
        assertThat(small.relay()).isEqualTo(10);
        assertThat(small.relay()).isEqualTo(5);
        assertThat(rows()).isZero();
    }

    @Test
    void twoRelaysRunningTogetherNeverPublishTheSameEventTwice() throws Exception {
        for (int i = 0; i < 5; i++) {
            writer.write(event("e" + i, "T" + i));
        }
        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch inside = new CountDownLatch(1);
        doAnswer(call -> {
            List<OutboundMessage> messages = call.getArgument(0);
            messages.forEach(m -> keys.add(m.key()));
            inside.countDown();
            Thread.sleep(500);
            return null;
        }).when(publisher).sendAll(anyList());
        OutboxRelay other = new OutboxRelay(jdbc, new TransactionTemplate(transactionManager), publisher, 100);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(relay::relay);
            inside.await();
            Future<Integer> second = pool.submit(other::relay);
            assertThat(first.get() + second.get()).isEqualTo(5);
        }

        assertThat(keys).hasSize(5).doesNotHaveDuplicates();
        assertThat(rows()).isZero();
    }
}
