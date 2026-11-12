package com.dws.trade.query;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.model.Trade;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One refused trade with the reason. */
@Document("trade_rejections")
public record RejectionDocument(@Id String id, @Indexed RejectionReason reason, String message, Trade trade,
        String source, @Indexed Instant at) {
}
