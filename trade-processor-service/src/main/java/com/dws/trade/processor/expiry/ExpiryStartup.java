package com.dws.trade.processor.expiry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Catches up on trades that matured while the service was down. It runs in the background so a
 * large backlog never delays start-up, and a failure only logs: the next scheduled run tries again.
 */
@Component
public class ExpiryStartup {

    private static final Logger log = LoggerFactory.getLogger(ExpiryStartup.class);

    private final ExpiryScheduler scheduler;
    private final boolean enabled;

    public ExpiryStartup(ExpiryScheduler scheduler, @Value("${trade.expiry.run-on-startup:true}") boolean enabled) {
        this.scheduler = scheduler;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        Thread.ofVirtual().name("expiry-startup").start(() -> {
            try {
                scheduler.scheduled();
            } catch (RuntimeException e) {
                log.error("The start-up expiry run failed; the next scheduled run will try again", e);
            }
        });
    }
}
