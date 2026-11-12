package com.dws.trade.processor.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives the relay: runs it again straight away while batches come back full, then pauses. */
@Component
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxRelay relay;

    public OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${trade.outbox.interval-ms}")
    public void run() {
        try {
            while (relay.relay() >= relay.batchSize()) {
                // keep draining a backlog
            }
        } catch (RuntimeException e) {
            // Kafka or the database is unavailable. The rows are still in the outbox; try again next time.
            log.warn("Outbox relay failed, will retry: {}", e.toString());
        }
    }
}
