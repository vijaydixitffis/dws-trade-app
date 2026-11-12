package com.dws.trade.processor.outbox;

/** A message waiting to be published to Kafka. */
public record OutboundMessage(String topic, String key, String payload) {
}
