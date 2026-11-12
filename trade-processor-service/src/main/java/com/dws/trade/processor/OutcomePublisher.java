package com.dws.trade.processor;

import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeRejection;

/** Announces what happened to a trade. Implementations return only once the outcome is durable. */
public interface OutcomePublisher {

    void event(TradeEvent event);

    void rejection(TradeRejection rejection);
}
