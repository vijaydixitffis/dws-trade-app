package com.dws.trade.processor.outbox;

import com.dws.trade.event.Topics;
import com.dws.trade.event.TradeEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an event to the outbox table. Call it inside the transaction that makes the change the
 * event describes, so the two succeed or fail together.
 */
@Component
public class OutboxWriter {

    private static final String INSERT = """
            INSERT INTO outbox (event_id, topic, message_key, payload) VALUES (?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void write(TradeEvent event) {
        jdbc.update(INSERT, event.eventId(), Topics.EVENTS, event.trade().tradeId(), mapper.writeValueAsString(event));
    }
}
