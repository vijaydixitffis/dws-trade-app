package com.dws.trade.event;

import com.dws.trade.model.StoredTrade;
import java.time.Instant;

/**
 * A change to the trade store. {@code eventId} is derived from the position of the inbound
 * Kafka record, so a redelivered record produces the same id and consumers can de-duplicate.
 */
public record TradeEvent(String eventId, TradeEventType type, StoredTrade trade, Instant at) {
}
