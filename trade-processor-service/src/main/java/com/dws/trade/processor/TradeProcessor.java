package com.dws.trade.processor;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeEventType;
import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.Trade;
import com.dws.trade.processor.outbox.OutboxWriter;
import com.dws.trade.processor.store.StoreResult;
import com.dws.trade.processor.store.TradeStore;
import com.dws.trade.rule.TradeRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offers each inbound trade to the store. The change to the store and the event that describes
 * it are saved in one transaction; a relay publishes the event afterwards.
 */
@Service
public class TradeProcessor {

    private static final Logger log = LoggerFactory.getLogger(TradeProcessor.class);

    private final TradeStore store;
    private final OutboxWriter outbox;
    private final OutcomePublisher publisher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meters;

    public TradeProcessor(TradeStore store, OutboxWriter outbox, OutcomePublisher publisher,
            TransactionTemplate transaction, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.outbox = outbox;
        this.publisher = publisher;
        this.transaction = transaction;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @param eventId identifies the inbound record; the same record always yields the same id
     */
    public void process(Trade trade, String eventId) {
        try {
            StoreResult result = transaction.execute(status -> {
                StoreResult saved = store.save(trade, LocalDate.now(clock));
                TradeEventType type = saved.replaced() ? TradeEventType.REPLACED : TradeEventType.ACCEPTED;
                outbox.write(new TradeEvent(eventId, type, saved.trade(), clock.instant()));
                return saved;
            });
            count(result.replaced() ? TradeEventType.REPLACED.name() : TradeEventType.ACCEPTED.name());
        } catch (TradeRejectedException e) {
            // A rejection is a normal business outcome, not a processing failure. The transaction
            // has rolled back, so nothing was stored; the rejection carries the same event id on
            // every attempt, and the history ignores a repeat.
            log.warn("Rejected: {}", e.getMessage());
            publisher.rejection(new TradeRejection(eventId, e.reason(), e.getMessage(), trade,
                    TradeRejection.SOURCE_PROCESSOR, clock.instant()));
            count(e.reason().name());
        }
    }

    /** Records a readable trade that failed validation; it never reaches the store. */
    public void rejectInvalid(Trade trade, String eventId, String message) {
        log.warn("Rejected: {}", message);
        publisher.rejection(new TradeRejection(eventId, RejectionReason.INVALID_TRADE, message, trade,
                TradeRejection.SOURCE_PROCESSOR, clock.instant()));
        count(RejectionReason.INVALID_TRADE.name());
    }

    private void count(String outcome) {
        meters.counter("trades.processed", "outcome", outcome).increment();
    }
}
