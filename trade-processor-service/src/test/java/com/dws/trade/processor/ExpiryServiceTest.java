package com.dws.trade.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeEventType;
import com.dws.trade.processor.expiry.ExpiryService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Rule 3: once a trade's maturity date has passed, the store marks it expired. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExpiryServiceTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final Instant NOW = TODAY.atTime(0, 1).atZone(ZONE).toInstant();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final OutcomePublisher publisher = mock(OutcomePublisher.class);

    @BeforeEach
    void emptyStore() {
        jdbc.update("TRUNCATE trade, trade_latest");
    }

    private ExpiryService service(int batchSize) {
        return new ExpiryService(jdbc, new TransactionTemplate(transactionManager), publisher,
                Clock.fixed(NOW, ZONE), batchSize);
    }

    private void insert(String id, int version, LocalDate maturity, String expired) {
        jdbc.update("INSERT INTO trade VALUES (?, ?, 'CP-1', 'B1', ?, ?, ?)", id, version, maturity,
                TODAY.minusDays(10), expired);
    }

    private String expiredFlag(String id) {
        return jdbc.queryForObject("SELECT expired FROM trade WHERE trade_id = ?", String.class, id);
    }

    @Test
    void marksOnlyTradesWhoseMaturityDateHasPassed() {
        insert("MATURED", 1, TODAY.minusDays(1), "N");
        insert("TODAY", 1, TODAY, "N");
        insert("FUTURE", 1, TODAY.plusDays(1), "N");

        int count = service(100).expireMatured();

        assertThat(count).isEqualTo(1);
        assertThat(expiredFlag("MATURED")).isEqualTo("Y");
        assertThat(expiredFlag("TODAY")).isEqualTo("N");
        assertThat(expiredFlag("FUTURE")).isEqualTo("N");
    }

    @Test
    void publishesOneExpiredEventPerTradeAndSkipsAlreadyExpiredTrades() {
        insert("MATURED", 3, TODAY.minusDays(1), "N");
        insert("OLD", 1, TODAY.minusDays(50), "Y");

        service(100).expireMatured();

        ArgumentCaptor<TradeEvent> event = ArgumentCaptor.forClass(TradeEvent.class);
        verify(publisher, times(1)).event(event.capture());
        assertThat(event.getValue().eventId()).isEqualTo("expired-MATURED-3");
        assertThat(event.getValue().type()).isEqualTo(TradeEventType.EXPIRED);
        assertThat(event.getValue().trade().expired()).isTrue();
        assertThat(event.getValue().at()).isEqualTo(NOW);
    }

    @Test
    void expiresEveryVersionOfAMaturedTradeAcrossSeveralBatches() {
        for (int v = 1; v <= 5; v++) {
            insert("MANY", v, TODAY.minusDays(1), "N");
        }

        int count = service(2).expireMatured();

        assertThat(count).isEqualTo(5);
        List<String> flags = jdbc.queryForList("SELECT expired FROM trade WHERE trade_id = 'MANY'", String.class);
        assertThat(flags).containsOnly("Y");
    }

    @Test
    void runningAgainFindsNothingToDo() {
        insert("MATURED", 1, TODAY.minusDays(1), "N");
        service(100).expireMatured();

        assertThat(service(100).expireMatured()).isZero();
    }
}
