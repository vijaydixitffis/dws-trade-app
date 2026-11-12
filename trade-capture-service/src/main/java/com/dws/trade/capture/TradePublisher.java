package com.dws.trade.capture;

import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.Trade;
import java.util.concurrent.CompletableFuture;

/** Hands trades to the stream. Each future completes when the broker has acknowledged the record. */
public interface TradePublisher {

    CompletableFuture<Void> inbound(Trade trade);

    CompletableFuture<Void> rejected(TradeRejection rejection);
}
