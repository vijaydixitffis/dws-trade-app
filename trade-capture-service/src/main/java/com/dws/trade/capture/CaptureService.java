package com.dws.trade.capture;

import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.Trade;
import com.dws.trade.rule.MaturityDatePassedException;
import com.dws.trade.rule.MaturityRule;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Decides what the capture API can decide alone, then hands the trade to the stream.
 * <p>
 * The maturity rule needs only the trade and today's date, so it is applied here and the
 * caller is told at once. The version rule needs the store and is left to the processor:
 * checking it here would race with trades still in flight.
 */
@Service
public class CaptureService {

    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    private final TradePublisher publisher;
    private final Clock clock;

    public CaptureService(TradePublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    public CaptureResult capture(Trade trade) {
        return captureAll(List.of(trade)).get(0);
    }

    /** Publishes every trade first, then waits once for all acknowledgements. */
    public List<CaptureResult> captureAll(List<Trade> trades) {
        LocalDate today = LocalDate.now(clock);
        List<CaptureResult> results = new ArrayList<>(trades.size());
        List<CompletableFuture<Void>> pending = new ArrayList<>(trades.size());
        for (Trade trade : trades) {
            try {
                MaturityRule.check(trade, today);
                pending.add(publisher.inbound(trade));
                results.add(new CaptureResult(trade.tradeId(), trade.version(),
                        CaptureResult.Status.ACCEPTED_FOR_PROCESSING, null, null));
            } catch (MaturityDatePassedException e) {
                pending.add(publisher.rejected(new TradeRejection("capture-" + UUID.randomUUID(), e.reason(),
                        e.getMessage(), trade, TradeRejection.SOURCE_CAPTURE, clock.instant())));
                results.add(new CaptureResult(trade.tradeId(), trade.version(),
                        CaptureResult.Status.REJECTED, e.reason(), e.getMessage()));
            }
        }
        awaitAcknowledgement(pending);
        return results;
    }

    private static void awaitAcknowledgement(List<CompletableFuture<Void>> pending) {
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StreamUnavailableException(e);
        } catch (Exception e) {
            throw new StreamUnavailableException(e);
        }
    }
}
