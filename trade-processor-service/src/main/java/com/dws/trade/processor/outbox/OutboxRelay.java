package com.dws.trade.processor.outbox;

import com.dws.trade.processor.OutcomePublisher;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes waiting outbox rows to Kafka, oldest first, and deletes them once Kafka has
 * acknowledged them. Publishing and deleting share one transaction: if publishing fails nothing
 * is deleted and the rows go out on the next run. A database advisory lock lets only one relay
 * run at a time across all processor instances, which keeps events in the order they were written.
 */
public class OutboxRelay {

    private static final long ADVISORY_LOCK_KEY = 704_201L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutcomePublisher publisher;
    private final int batchSize;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate transaction, OutcomePublisher publisher, int batchSize) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    public int batchSize() {
        return batchSize;
    }

    private record Row(long id, OutboundMessage message) {
    }

    /** @return the number of messages published; 0 when idle or when another instance holds the lock */
    public int relay() {
        Integer published = transaction.execute(status -> {
            Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                    ADVISORY_LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            List<Row> rows = jdbc.query("""
                            SELECT id, topic, message_key, payload FROM outbox ORDER BY id LIMIT ?""",
                    (rs, i) -> new Row(rs.getLong("id"), new OutboundMessage(rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("payload"))),
                    batchSize);
            if (rows.isEmpty()) {
                return 0;
            }
            publisher.sendAll(rows.stream().map(Row::message).toList());
            jdbc.batchUpdate("DELETE FROM outbox WHERE id = ?", rows.stream().map(r -> new Object[] {r.id()}).toList());
            return rows.size();
        });
        return published == null ? 0 : published;
    }
}
