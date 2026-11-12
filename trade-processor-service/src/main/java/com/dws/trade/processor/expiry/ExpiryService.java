package com.dws.trade.processor.expiry;

import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeEventType;
import com.dws.trade.model.StoredTrade;
import com.dws.trade.processor.OutcomePublisher;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Rule 3: marks every trade whose maturity date has passed as expired. */
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);

    /** SKIP LOCKED lets the job run beside trade processing without waiting on row locks. */
    private static final String EXPIRE_BATCH = """
            UPDATE trade SET expired = 'Y'
            WHERE (trade_id, version) IN (
                SELECT trade_id, version FROM trade
                WHERE expired = 'N' AND maturity_date < ?
                ORDER BY trade_id, version
                LIMIT ?
                FOR UPDATE SKIP LOCKED)
            RETURNING trade_id, version, counter_party_id, book_id, maturity_date, created_date
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutcomePublisher publisher;
    private final Clock clock;
    private final int batchSize;

    public ExpiryService(JdbcTemplate jdbc, TransactionTemplate transaction, OutcomePublisher publisher,
            Clock clock, int batchSize) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.publisher = publisher;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** @return the number of trades marked expired */
    public int expireMatured() {
        LocalDate today = LocalDate.now(clock);
        int total = 0;
        List<StoredTrade> batch;
        do {
            batch = transaction.execute(status -> jdbc.query(EXPIRE_BATCH,
                    (rs, row) -> new StoredTrade(rs.getString("trade_id"), rs.getInt("version"),
                            rs.getString("counter_party_id"), rs.getString("book_id"),
                            rs.getObject("maturity_date", LocalDate.class),
                            rs.getObject("created_date", LocalDate.class), true),
                    today, batchSize));
            for (StoredTrade trade : batch) {
                publisher.event(new TradeEvent("expired-%s-%d".formatted(trade.tradeId(), trade.version()),
                        TradeEventType.EXPIRED, trade, clock.instant()));
            }
            total += batch.size();
        } while (batch.size() == batchSize);
        if (total > 0) {
            log.info("Marked {} trade(s) expired; maturity date before {}", total, today);
        }
        return total;
    }
}
