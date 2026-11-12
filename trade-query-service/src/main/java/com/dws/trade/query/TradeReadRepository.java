package com.dws.trade.query;

import com.dws.trade.model.StoredTrade;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only view of the trade store in PostgreSQL. The list is in store sequence (trade id
 * ascending, version descending) and pages by keyset, so a deep page costs the same as the first.
 */
@Repository
public class TradeReadRepository {

    /** Counting stops here; beyond it the dashboard shows "10,000+" instead of scanning the table. */
    public static final int COUNT_CAP = 10_000;

    public record Count(long total, boolean exact) {
    }

    private final JdbcTemplate jdbc;

    public TradeReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The SQL for one page. Parameters, in order: prefix lower and upper bound (when {@code prefix}),
     * cursor trade id, trade id again, version (when {@code cursor}), then the limit.
     */
    static String findSql(boolean prefix, boolean cursor) {
        StringBuilder sql = new StringBuilder("""
                SELECT trade_id, version, counter_party_id, book_id, maturity_date, created_date, expired
                FROM trade WHERE true""");
        if (prefix) {
            sql.append(" AND trade_id COLLATE \"C\" >= ? AND trade_id COLLATE \"C\" < ?");
        }
        if (cursor) {
            // The first condition is the index bound; the second removes the rows already shown.
            sql.append(" AND trade_id COLLATE \"C\" >= ? AND (trade_id COLLATE \"C\" > ? OR version < ?)");
        }
        return sql.append(" ORDER BY trade_id COLLATE \"C\" ASC, version DESC LIMIT ?").toString();
    }

    /** Trades in store sequence whose id starts with the prefix (case-sensitive), after the cursor. */
    public List<StoredTrade> find(String tradeIdPrefix, Cursor after, int limit) {
        boolean prefix = hasPrefix(tradeIdPrefix);
        List<Object> params = new ArrayList<>();
        if (prefix) {
            params.add(tradeIdPrefix);
            params.add(upperBound(tradeIdPrefix));
        }
        if (after != null) {
            params.add(after.tradeId());
            params.add(after.tradeId());
            params.add(after.version());
        }
        params.add(limit);
        return jdbc.query(findSql(prefix, after != null),
                (rs, row) -> new StoredTrade(rs.getString("trade_id"), rs.getInt("version"),
                        rs.getString("counter_party_id"), rs.getString("book_id"),
                        rs.getObject("maturity_date", LocalDate.class),
                        rs.getObject("created_date", LocalDate.class), "Y".equals(rs.getString("expired"))),
                params.toArray());
    }

    /** The number of matching trades, counted only up to {@link #COUNT_CAP}. */
    public Count count(String tradeIdPrefix) {
        boolean prefix = hasPrefix(tradeIdPrefix);
        String sql = "SELECT count(*) FROM (SELECT 1 FROM trade WHERE true"
                + (prefix ? " AND trade_id COLLATE \"C\" >= ? AND trade_id COLLATE \"C\" < ?" : "")
                + " LIMIT ?) c";
        Long counted = prefix
                ? jdbc.queryForObject(sql, Long.class, tradeIdPrefix, upperBound(tradeIdPrefix), COUNT_CAP + 1)
                : jdbc.queryForObject(sql, Long.class, COUNT_CAP + 1);
        long n = counted == null ? 0 : counted;
        return n > COUNT_CAP ? new Count(COUNT_CAP, false) : new Count(n, true);
    }

    /** Every row, exactly. A full scan: callers cache the answer. */
    public long countExact() {
        return jdbc.queryForObject("SELECT count(*) FROM trade", Long.class);
    }

    public long countExpired() {
        return jdbc.queryForObject("SELECT count(*) FROM trade WHERE expired = 'Y'", Long.class);
    }

    private static boolean hasPrefix(String prefix) {
        return prefix != null && !prefix.isEmpty();
    }

    /** The smallest string above every string that starts with the prefix. */
    private static String upperBound(String prefix) {
        int last = prefix.length() - 1;
        return prefix.substring(0, last) + (char) (prefix.charAt(last) + 1);
    }
}
