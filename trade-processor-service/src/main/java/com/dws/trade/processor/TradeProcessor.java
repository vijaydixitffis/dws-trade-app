package com.dws.trade.processor;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeEventType;
import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.Trade;
import com.dws.trade.processor.store.StoreResult;
import com.dws.trade.processor.store.TradeStore;
import com.dws.trade.rule.TradeRejectedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Offers each inbound trade to the store and publishes the outcome. */
@Service
public class TradeProcessor {

    private static final Logger log = LoggerFactory.getLogger(TradeProcessor.class);

    private final TradeStore store;
    private final OutcomePublisher publisher;
    private final Clock clock;
    private final MeterRegistry meters;

    public TradeProcessor(TradeStore store, OutcomePublisher publisher, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * @param eventId identifies the inbound record; the same record always yields the same id
     */
    public void process(Trade trade, String eventId) {
        try {
            StoreResult result = store.save(trade, LocalDate.now(clock));
            TradeEventType type = result.replaced() ? TradeEventType.REPLACED : TradeEventType.ACCEPTED;
            publisher.event(new TradeEvent(eventId, type, result.trade(), clock.instant()));
            count(type.name());
        } catch (TradeRejectedException e) {
            // A rejection is a normal business outcome, not a processing failure.
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
