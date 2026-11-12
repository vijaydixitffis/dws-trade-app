package com.dws.trade.query;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The dashboard's headline counts. They need full scans, so they are counted once per cache
 * lifetime and shared by every browser: read load no longer grows with the number of viewers.
 */
@Service
public class SummaryService {

    public record Summary(LocalDate businessDate, String businessZone, long trades, long expired, long accepted,
            long replaced, long rejectedLowerVersion, long rejectedMaturityPassed, long rejectedInvalid) {
    }

    private record Cached(Summary summary, long loadedAtNanos) {
    }

    private final TradeReadRepository trades;
    private final History history;
    private final Clock clock;
    private final long lifetimeNanos;
    private final LongSupplier nanoTime;
    private Cached cached;

    @Autowired
    public SummaryService(TradeReadRepository trades, History history, Clock clock,
            @Value("${trade.summary.cache-ms:2000}") long cacheMillis) {
        this(trades, history, clock, Duration.ofMillis(cacheMillis), System::nanoTime);
    }

    SummaryService(TradeReadRepository trades, History history, Clock clock, Duration lifetime, LongSupplier nanoTime) {
        this.trades = trades;
        this.history = history;
        this.clock = clock;
        this.lifetimeNanos = lifetime.toNanos();
        this.nanoTime = nanoTime;
    }

    /** Callers that arrive while a count is running wait for it instead of starting their own. */
    public synchronized Summary get() {
        long now = nanoTime.getAsLong();
        if (cached == null || now - cached.loadedAtNanos() >= lifetimeNanos) {
            cached = new Cached(load(), now);
        }
        return cached.summary();
    }

    private Summary load() {
        return new Summary(LocalDate.now(clock), clock.getZone().getId(), trades.countExact(), trades.countExpired(),
                history.countEvents(TradeEventType.ACCEPTED.name()),
                history.countEvents(TradeEventType.REPLACED.name()),
                history.countRejections(RejectionReason.LOWER_VERSION.name()),
                history.countRejections(RejectionReason.MATURITY_DATE_PASSED.name()),
                history.countRejections(RejectionReason.INVALID_TRADE.name()));
    }
}
