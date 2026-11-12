package com.dws.trade.query;

/** Where the previous page of the trade list ended: the last trade id and version returned. */
public record Cursor(String tradeId, int version) {
}
