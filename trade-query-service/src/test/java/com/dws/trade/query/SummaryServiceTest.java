package com.dws.trade.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The summary is counted once per lifetime however many browsers ask for it. */
class SummaryServiceTest {

    private final TradeReadRepository trades = mock(TradeReadRepository.class);
    private final History history = mock(History.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneId.of("Asia/Kolkata"));
    private final SummaryService service = new SummaryService(trades, history, clock, Duration.ofSeconds(2), nanos::get);

    SummaryServiceTest() {
        when(trades.countExact()).thenReturn(10L);
        when(trades.countExpired()).thenReturn(3L);
        when(history.countEvents(TradeEventType.ACCEPTED.name())).thenReturn(7L);
        when(history.countEvents(TradeEventType.REPLACED.name())).thenReturn(2L);
        when(history.countRejections(RejectionReason.LOWER_VERSION.name())).thenReturn(4L);
        when(history.countRejections(RejectionReason.MATURITY_DATE_PASSED.name())).thenReturn(5L);
        when(history.countRejections(RejectionReason.INVALID_TRADE.name())).thenReturn(6L);
    }

    @Test
    void reportsEveryCountAndTheBusinessDate() {
        SummaryService.Summary summary = service.get();

        assertThat(summary).isEqualTo(new SummaryService.Summary(java.time.LocalDate.of(2026, 11, 12), "Asia/Kolkata",
                10, 3, 7, 2, 4, 5, 6));
    }

    @Test
    void answersFromTheCacheWithinItsLifetimeAndCountsAgainAfterwards() {
        service.get();
        nanos.set(Duration.ofMillis(1_900).toNanos());
        service.get();
        verify(trades, times(1)).countExact();

        nanos.set(Duration.ofMillis(2_100).toNanos());
        service.get();
        verify(trades, times(2)).countExact();
    }

    @Test
    void manyCallersAtOnceTriggerOneCount() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(trades.countExact()).thenAnswer(call -> {
            release.await();
            return 10L;
        });

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<SummaryService.Summary>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(service::get));
            }
            Thread.sleep(200);
            release.countDown();
            for (Future<SummaryService.Summary> result : results) {
                assertThat(result.get().trades()).isEqualTo(10);
            }
        }

        verify(trades, times(1)).countExact();
    }
}
