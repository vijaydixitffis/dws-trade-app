package com.dws.trade.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.Trade;
import com.dws.trade.processor.outbox.OutboxWriter;
import com.dws.trade.processor.store.TradeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The trade and its event are saved together or not at all. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TradeStore.class)
class TradeProcessorAtomicityTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate FUTURE = LocalDate.of(2027, 5, 20);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
    }

    @Autowired
    TradeStore store;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final OutcomePublisher publisher = mock(OutcomePublisher.class);

    @BeforeEach
    void emptyTables() {
        jdbc.update("TRUNCATE trade, trade_latest, outbox");
    }

    private TradeProcessor processor(OutboxWriter outbox) {
        return new TradeProcessor(store, outbox, publisher, new TransactionTemplate(transactionManager), CLOCK,
                new SimpleMeterRegistry());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void aFailureWritingTheEventRollsBackTheTradeToo() {
        OutboxWriter failing = mock(OutboxWriter.class);
        doThrow(new IllegalStateException("outbox down")).when(failing).write(any());

        assertThatThrownBy(() -> processor(failing).process(new Trade("T1", 1, "CP-1", "B1", FUTURE), "e1"))
                .hasMessage("outbox down");

        assertThat(count("trade")).isZero();
        assertThat(count("trade_latest")).isZero();
    }

    @Test
    void anAcceptedTradeLeavesItsRowAndItsEventTogether() {
        TradeProcessor processor = processor(new OutboxWriter(jdbc, JsonMapper.builder().findAndAddModules().build()));

        processor.process(new Trade("T1", 1, "CP-1", "B1", FUTURE), "e1");
        processor.process(new Trade("T1", 1, "CP-9", "B1", FUTURE), "e2");

        assertThat(count("trade")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT payload FROM outbox ORDER BY id", String.class))
                .hasSize(2)
                .satisfies(payloads -> {
                    assertThat(payloads.get(0)).contains("\"type\":\"ACCEPTED\"");
                    assertThat(payloads.get(1)).contains("\"type\":\"REPLACED\"");
                });
    }

    @Test
    void aRejectedTradeWritesNoEventAndChangesNothing() {
        TradeProcessor processor = processor(new OutboxWriter(jdbc, JsonMapper.builder().findAndAddModules().build()));
        processor.process(new Trade("T1", 2, "CP-1", "B1", FUTURE), "e1");

        processor.process(new Trade("T1", 1, "CP-9", "B9", FUTURE), "e2");

        assertThat(count("outbox")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT book_id FROM trade WHERE trade_id = 'T1'", String.class)).isEqualTo("B1");
        ArgumentCaptor<TradeRejection> rejection = ArgumentCaptor.forClass(TradeRejection.class);
        verify(publisher).rejection(rejection.capture());
        assertThat(rejection.getValue().reason()).isEqualTo(RejectionReason.LOWER_VERSION);
    }
}
