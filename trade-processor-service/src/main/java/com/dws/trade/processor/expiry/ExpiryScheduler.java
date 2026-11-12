package com.dws.trade.processor.expiry;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the expiry job shortly after midnight in the business time zone and once at startup,
 * so trades that matured while the service was down are caught. The lock keeps the scheduled
 * run to one processor instance.
 */
@Component
public class ExpiryScheduler {

    private final ExpiryService expiryService;

    public ExpiryScheduler(ExpiryService expiryService) {
        this.expiryService = expiryService;
    }

    @Scheduled(cron = "${trade.expiry.cron}", zone = "${trade.business-zone:}")
    @SchedulerLock(name = "trade-expiry", lockAtLeastFor = "PT5S", lockAtMostFor = "PT30M")
    public void scheduled() {
        expiryService.expireMatured();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        expiryService.expireMatured();
    }
}
