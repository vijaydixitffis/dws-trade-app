package com.dws.trade.processor.expiry;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the expiry job shortly after midnight in the business time zone. The lock keeps the run
 * to one processor instance. {@link ExpiryStartup} calls {@link #scheduled()} once at start-up
 * through this same bean, so the catch-up run takes the lock too.
 */
@Component
public class ExpiryScheduler {

    private final ExpiryService expiryService;

    public ExpiryScheduler(ExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(cron = "${trade.expiry.cron}", zone = "${trade.business-zone:}")
    @SchedulerLock(name = "trade-expiry", lockAtLeastFor = "${trade.expiry.lock-at-least:PT5S}",
            lockAtMostFor = "PT30M")
    public void scheduled() {
        expiryService.expireMatured();
    }
}
