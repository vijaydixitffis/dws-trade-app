package com.dws.trade.query;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeEventType;
import com.dws.trade.model.StoredTrade;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private static final int MAX_PAGE = 500;

    private final TradeReadRepository trades;
    private final History history;
    private final LiveFeed liveFeed;
    private final Clock clock;

    public QueryController(TradeReadRepository trades, History history, LiveFeed liveFeed, Clock clock) {
        this.trades = trades;
        this.history = history;
        this.liveFeed = liveFeed;
        this.clock = clock;
    }

    public record TradePage(long total, int page, int size, List<StoredTrade> items) {
    }

    public record Summary(LocalDate businessDate, String businessZone, long trades, long expired, long accepted,
            long replaced, long rejectedLowerVersion, long rejectedMaturityPassed) {
    }

    @GetMapping("/trades")
    public TradePage trades(@RequestParam(required = false) String tradeId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        int pageSize = Math.clamp(size, 1, MAX_PAGE);
        int pageIndex = Math.max(page, 0);
        return new TradePage(trades.count(tradeId), pageIndex, pageSize,
                trades.find(tradeId, pageSize, pageIndex * pageSize));
    }

    @GetMapping("/events")
    public List<EventDocument> events(@RequestParam(defaultValue = "100") int limit) {
        return history.latestEvents(Math.clamp(limit, 1, MAX_PAGE));
    }

    @GetMapping("/rejections")
    public List<RejectionDocument> rejections(@RequestParam(defaultValue = "100") int limit) {
        return history.latestRejections(Math.clamp(limit, 1, MAX_PAGE));
    }

    @GetMapping("/summary")
    public Summary summary() {
        return new Summary(LocalDate.now(clock), clock.getZone().getId(), trades.count(null), trades.countExpired(),
                history.countEvents(TradeEventType.ACCEPTED.name()),
                history.countEvents(TradeEventType.REPLACED.name()),
                history.countRejections(RejectionReason.LOWER_VERSION.name()),
                history.countRejections(RejectionReason.MATURITY_DATE_PASSED.name()));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return liveFeed.subscribe();
    }
}
