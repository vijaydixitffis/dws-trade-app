package com.dws.trade.processor;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.dws.trade.processor.expiry.ExpiryScheduler;
import com.dws.trade.processor.expiry.ExpiryStartup;
import org.junit.jupiter.api.Test;

/** The catch-up run at start-up never delays or breaks start-up. */
class ExpiryStartupTest {

    private final ExpiryScheduler scheduler = mock(ExpiryScheduler.class);

    @Test
    void runsTheScheduledJobInTheBackground() {
        new ExpiryStartup(scheduler, true).onReady();

        verify(scheduler, timeout(2_000)).scheduled();
    }

    @Test
    void survivesAFailureInTheJob() {
        doThrow(new IllegalStateException("kafka down")).when(scheduler).scheduled();

        new ExpiryStartup(scheduler, true).onReady();

        verify(scheduler, timeout(2_000)).scheduled();
    }

    @Test
    void doesNothingWhenSwitchedOff() throws Exception {
        new ExpiryStartup(scheduler, false).onReady();

        Thread.sleep(300);
        verify(scheduler, never()).scheduled();
    }
}
