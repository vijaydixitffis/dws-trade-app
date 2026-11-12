package com.dws.trade.processor;

import com.dws.trade.event.TradeRejection;
import com.dws.trade.processor.outbox.OutboundMessage;
import java.util.List;

/** Announces outcomes on Kafka. Implementations return only once Kafka has acknowledged them. */
public interface OutcomePublisher {

    /** Publishes a rejection. Rejections change nothing in the store, so they are sent directly. */
    void rejection(TradeRejection rejection);

    /** Publishes outbox messages in order and waits for the broker to acknowledge all of them. */
    void sendAll(List<OutboundMessage> messages);
}
