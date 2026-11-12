package com.dws.trade.event;

public enum TradeEventType {
    /** A new (trade id, version) row was added. */
    ACCEPTED,
    /** An existing row with the same version was replaced. */
    REPLACED,
    /** The maturity date passed and the row was marked expired. */
    EXPIRED
}
