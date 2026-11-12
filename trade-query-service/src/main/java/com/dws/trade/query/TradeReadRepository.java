package com.dws.trade.query;

import com.dws.trade.model.StoredTrade;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only view of the trade store in PostgreSQL. */
@Repository
public class TradeReadRepository {

    private final JdbcTemplate jdbc;

    public TradeReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Trades in store sequence: trade id ascending, then version descending. */
    public List<StoredTrade> find(String tradeIdPrefix, int limit, int offset) {
        return jdbc.query("""
                        SELECT trade_id, version, counter_party_id, book_id, maturity_date, created_date, expired
                        FROM trade
                        WHERE upper(trade_id) LIKE ?
                        ORDER BY trade_id ASC, version DESC
                        LIMIT ? OFFSET ?
                        """,
                (rs, row) -> new StoredTrade(rs.getString("trade_id"), rs.getInt("version"),
                        rs.getString("counter_party_id"), rs.getString("book_id"),
                        rs.getObject("maturity_date", LocalDate.class),
                        rs.getObject("created_date", LocalDate.class), "Y".equals(rs.getString("expired"))),
                pattern(tradeIdPrefix), limit, offset);
    }

    public long count(String tradeIdPrefix) {
        return jdbc.queryForObject("SELECT count(*) FROM trade WHERE upper(trade_id) LIKE ?", Long.class,
                pattern(tradeIdPrefix));
    }

    public long countExpired() {
        return jdbc.queryForObject("SELECT count(*) FROM trade WHERE expired = 'Y'", Long.class);
    }

    private static String pattern(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "%";
        }
        // Escape LIKE wildcards so the filter is a literal prefix.
        return prefix.trim().toUpperCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
