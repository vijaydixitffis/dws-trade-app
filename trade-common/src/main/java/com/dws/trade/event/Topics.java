package com.dws.trade.event;

/** Kafka topic names shared by the services. */
public final class Topics {

    /** Trades awaiting processing; key = trade id, so versions of one trade stay in order. */
    public static final String INBOUND = "trades.inbound";
    /** Accepted, replaced and expired trades. */
    public static final String EVENTS = "trades.events";
    /** Trades refused by a business rule. */
    public static final String REJECTED = "trades.rejected";
    /** Records that could not be processed for technical reasons. */
    public static final String INBOUND_DLT = "trades.inbound.DLT";

    private Topics() {
    }
}
