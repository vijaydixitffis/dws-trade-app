package com.dws.trade.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeEventType;
import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.StoredTrade;
import com.dws.trade.model.Trade;
import com.dws.trade.processor.outbox.OutboxWriter;
import com.dws.trade.processor.store.StoreResult;
import com.dws.trade.processor.store.TradeStore;
import com.dws.trade.rule.LowerVersionTradeException;
import com.dws.trade.rule.MaturityDatePassedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The processor turns each store outcome into exactly one event or one rejection. */
class TradeProcessorTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/London");
    private static final Instant NOW = Instant.parse("2026-11-12T10:15:30Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final Trade TRADE = new Trade("T1", 2, "CP-1", "B1", TODAY.plusDays(30));
    private static final StoredTrade STORED = new StoredTrade("T1", 2, "CP-1", "B1", TODAY.plusDays(30), TODAY, false);

    private final TradeStore store = mock(TradeStore.class);
    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final OutcomePublisher publisher = mock(OutcomePublisher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final TransactionTemplate transaction = new TransactionTemplate(mock(PlatformTransactionManager.class));
    private final TradeProcessor processor =
            new TradeProcessor(store, outbox, publisher, transaction, Clock.fixed(NOW, ZONE), meters);

    @Test
    void publishesAcceptedEventForANewRow() {
        when(store.save(TRADE, TODAY)).thenReturn(new StoreResult(STORED, false));

        processor.process(TRADE, "trades.inbound-0-7");

        verify(outbox).write(new TradeEvent("trades.inbound-0-7", TradeEventType.ACCEPTED, STORED, NOW));
        verify(publisher, never()).rejection(any());
        assertThat(meters.counter("trades.processed", "outcome", "ACCEPTED").count()).isEqualTo(1);
    }

    @Test
    void publishesReplacedEventWhenTheSameVersionWasOverwritten() {
        when(store.save(TRADE, TODAY)).thenReturn(new StoreResult(STORED, true));

        processor.process(TRADE, "e1");

        verify(outbox).write(new TradeEvent("e1", TradeEventType.REPLACED, STORED, NOW));
    }

    @Test
    void publishesRejectionWhenTheStoreThrowsLowerVersion() {
        when(store.save(TRADE, TODAY)).thenThrow(new LowerVersionTradeException("T1", 2, 5));

        processor.process(TRADE, "e2");

        ArgumentCaptor<TradeRejection> rejection = ArgumentCaptor.forClass(TradeRejection.class);
        verify(publisher).rejection(rejection.capture());
        verify(outbox, never()).write(any());
        assertThat(rejection.getValue()).isEqualTo(new TradeRejection("e2", RejectionReason.LOWER_VERSION,
                "Trade T1 version 2 is lower than the stored version 5", TRADE, TradeRejection.SOURCE_PROCESSOR, NOW));
        assertThat(meters.counter("trades.processed", "outcome", "LOWER_VERSION").count()).isEqualTo(1);
    }

    @Test
    void publishesRejectionWhenTheStoreThrowsMaturityPassed() {
        when(store.save(TRADE, TODAY)).thenThrow(new MaturityDatePassedException("T1", TODAY.minusDays(1), TODAY));

        processor.process(TRADE, "e3");

        ArgumentCaptor<TradeRejection> rejection = ArgumentCaptor.forClass(TradeRejection.class);
        verify(publisher).rejection(rejection.capture());
        assertThat(rejection.getValue().reason()).isEqualTo(RejectionReason.MATURITY_DATE_PASSED);
    }

    @Test
    void todayIsTakenFromTheBusinessZoneOfTheClock() {
        // 23:30 UTC on the 12th is already the 13th in Tokyo.
        Clock tokyo = Clock.fixed(Instant.parse("2026-11-12T23:30:00Z"), ZoneId.of("Asia/Tokyo"));
        TradeProcessor inTokyo = new TradeProcessor(store, outbox, publisher, transaction, tokyo, meters);
        when(store.save(TRADE, LocalDate.of(2026, 11, 13))).thenReturn(new StoreResult(STORED, false));

        inTokyo.process(TRADE, "e4");

        verify(store).save(TRADE, LocalDate.of(2026, 11, 13));
    }

    @Test
    void recordsAnInvalidTradeAsARejectionWithoutTouchingTheStore() {
        Trade broken = new Trade("T1", 1, null, "B1", TODAY);

        processor.rejectInvalid(broken, "e9", "Invalid trade: counterPartyId must not be blank");

        ArgumentCaptor<TradeRejection> rejection = ArgumentCaptor.forClass(TradeRejection.class);
        verify(publisher).rejection(rejection.capture());
        verify(store, never()).save(any(), any());
        assertThat(rejection.getValue()).isEqualTo(new TradeRejection("e9", RejectionReason.INVALID_TRADE,
                "Invalid trade: counterPartyId must not be blank", broken, TradeRejection.SOURCE_PROCESSOR, NOW));
        assertThat(meters.counter("trades.processed", "outcome", "INVALID_TRADE").count()).isEqualTo(1);
    }
}
