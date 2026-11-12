package com.dws.trade.processor.store;

import com.dws.trade.model.StoredTrade;
import com.dws.trade.model.Trade;
import com.dws.trade.rule.LowerVersionTradeException;
import com.dws.trade.rule.MaturityDatePassedException;
import com.dws.trade.rule.MaturityRule;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trade store. Holds one row per (trade id, version) and enforces the arrival rules:
 * a maturity date earlier than today is rejected, a version lower than the highest stored
 * version is rejected, the same version replaces its row, and a higher version adds a row.
 */
@Repository
public class TradeStore {

    /**
     * Claims the version for the trade. Affects no row when a higher version is already
     * recorded. The row lock it takes makes concurrent writers of one trade queue up, so the
     * check stays correct with several processor instances.
     */
    private static final String CLAIM_VERSION = """
            INSERT INTO trade_latest (trade_id, latest_version) VALUES (?, ?)
            ON CONFLICT (trade_id) DO UPDATE SET latest_version = EXCLUDED.latest_version
            WHERE trade_latest.latest_version <= EXCLUDED.latest_version
            """;

    /** Inserts or replaces the row; {@code xmax = 0} is true only for a freshly inserted row. */
    private static final String UPSERT_TRADE = """
            INSERT INTO trade (trade_id, version, counter_party_id, book_id, maturity_date, created_date, expired)
            VALUES (?, ?, ?, ?, ?, ?, 'N')
            ON CONFLICT (trade_id, version) DO UPDATE SET
                counter_party_id = EXCLUDED.counter_party_id,
                book_id          = EXCLUDED.book_id,
                maturity_date    = EXCLUDED.maturity_date,
                created_date     = EXCLUDED.created_date,
                expired          = 'N'
            RETURNING (xmax = 0) AS inserted
            """;

    private final JdbcTemplate jdbc;

    public TradeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @throws MaturityDatePassedException if the maturity date is earlier than {@code today}
     * @throws LowerVersionTradeException  if a higher version of the trade is already stored
     */
    @Transactional
    public StoreResult save(Trade trade, LocalDate today) {
        MaturityRule.check(trade, today);

        int claimed = jdbc.update(CLAIM_VERSION, trade.tradeId(), trade.version());
        if (claimed == 0) {
            Integer stored = jdbc.queryForObject(
                    "SELECT latest_version FROM trade_latest WHERE trade_id = ?", Integer.class, trade.tradeId());
            throw new LowerVersionTradeException(trade.tradeId(), trade.version(), stored);
        }

        Boolean inserted = jdbc.queryForObject(UPSERT_TRADE, Boolean.class, trade.tradeId(), trade.version(),
                trade.counterPartyId(), trade.bookId(), trade.maturityDate(), today);
        StoredTrade stored = new StoredTrade(trade.tradeId(), trade.version(), trade.counterPartyId(),
                trade.bookId(), trade.maturityDate(), today, false);
        return new StoreResult(stored, !Boolean.TRUE.equals(inserted));
    }
}
