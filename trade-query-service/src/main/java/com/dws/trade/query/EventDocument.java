package com.dws.trade.query;

import com.dws.trade.event.TradeEventType;
import com.dws.trade.model.StoredTrade;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One change to the trade store. The id is the event id, so a redelivery overwrites itself. */
@Document("trade_events")
public record EventDocument(@Id String id, @Indexed TradeEventType type, StoredTrade trade, @Indexed Instant at) {
}
