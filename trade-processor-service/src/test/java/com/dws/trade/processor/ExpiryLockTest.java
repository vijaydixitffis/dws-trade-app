package com.dws.trade.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.dws.trade.processor.expiry.ExpiryScheduler;
import com.dws.trade.processor.expiry.ExpiryService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Two processor instances must not run the daily expiry job at the same time. */
@SpringBootTest(properties = {
        "trade.expiry.cron=-",
        "trade.expiry.run-on-startup=false",
        "trade.expiry.lock-at-least=PT0S",
        "trade.outbox.interval-ms=3600000"})
class ExpiryLockTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
        Containers.kafka(registry);
    }

    @Autowired
    ExpiryScheduler scheduler;
    @MockitoSpyBean
    ExpiryService expiryService;

    @Test
    void onlyOneOfTwoSimultaneousRunsDoesTheWork() throws Exception {
        reset(expiryService);
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            Thread.sleep(1_000);
            return 0;
        }).when(expiryService).expireMatured();

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(scheduler::scheduled);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = pool.submit(scheduler::scheduled);
            first.get();
            second.get();
        }

        verify(expiryService, times(1)).expireMatured();
    }
}
