package com.dws.trade.event;

import com.dws.trade.model.Trade;
import java.time.Instant;

/** A trade the store refused, with the reason. {@code source} is CAPTURE or PROCESSOR. */
public record TradeRejection(
        String eventId, RejectionReason reason, String message, Trade trade, String source, Instant at) {

    public static final String SOURCE_CAPTURE = "CAPTURE";
    public static final String SOURCE_PROCESSOR = "PROCESSOR";
}
