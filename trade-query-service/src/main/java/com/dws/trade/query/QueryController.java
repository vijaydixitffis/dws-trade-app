package com.dws.trade.query;

import com.dws.trade.model.StoredTrade;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class QueryController {

    private static final int MAX_PAGE = 500;
    private static final Pattern TRADE_ID = Pattern.compile("[A-Za-z0-9._-]{0,64}");

    private final TradeReadRepository trades;
    private final History history;
    private final LiveFeed liveFeed;
    private final SummaryService summary;

    public QueryController(TradeReadRepository trades, History history, LiveFeed liveFeed, SummaryService summary) {
        this.trades = trades;
        this.history = history;
        this.liveFeed = liveFeed;
        this.summary = summary;
    }

    /**
     * One page of the store. {@code total} is exact up to 10,000 ({@code totalExact} says whether it
     * is); {@code next} is the cursor for the following page, or null on the last page.
     */
    public record TradePage(long total, boolean totalExact, int size, List<StoredTrade> items, Cursor next) {
    }

    @GetMapping("/trades")
    public TradePage trades(@RequestParam(required = false) String tradeId,
            @RequestParam(required = false) String afterTradeId, @RequestParam(required = false) Integer afterVersion,
            @RequestParam(defaultValue = "50") int size) {
        if (tradeId != null && !TRADE_ID.matcher(tradeId).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tradeId may use letters, digits, dot, underscore and hyphen, up to 64 characters");
        }
        if ((afterTradeId == null) != (afterVersion == null)
                || (afterTradeId != null && !TRADE_ID.matcher(afterTradeId).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "afterTradeId and afterVersion must be given together and be valid");
        }
        int pageSize = Math.clamp(size, 1, MAX_PAGE);
        Cursor after = afterTradeId == null ? null : new Cursor(afterTradeId, afterVersion);
        // One extra row tells whether another page follows.
        List<StoredTrade> rows = trades.find(tradeId, after, pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<StoredTrade> items = more ? rows.subList(0, pageSize) : rows;
        StoredTrade last = items.isEmpty() ? null : items.get(items.size() - 1);
        TradeReadRepository.Count count = trades.count(tradeId);
        return new TradePage(count.total(), count.exact(), pageSize, items,
                more ? new Cursor(last.tradeId(), last.version()) : null);
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
    public SummaryService.Summary summary() {
        return summary.get();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return liveFeed.subscribe();
    }

    @ExceptionHandler(LiveFeed.TooManySubscribersException.class)
    ProblemDetail tooManyDashboards(LiveFeed.TooManySubscribersException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
