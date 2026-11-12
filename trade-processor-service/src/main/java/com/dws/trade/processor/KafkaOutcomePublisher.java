package com.dws.trade.processor;

import com.dws.trade.event.Topics;
import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeRejection;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outcomes to Kafka and waits for the broker to acknowledge them. A failure here
 * fails the inbound record, which is then retried; reprocessing a trade is harmless.
 */
@Component
public class KafkaOutcomePublisher implements OutcomePublisher {

    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public KafkaOutcomePublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Override
    public void event(TradeEvent event) {
        send(Topics.EVENTS, event.trade().tradeId(), event);
    }

    @Override
    public void rejection(TradeRejection rejection) {
        send(Topics.REJECTED, rejection.trade().tradeId(), rejection);
    }

    private void send(String topic, String key, Object payload) {
        try {
            kafka.send(topic, key, mapper.writeValueAsString(payload)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing to " + topic, e);
        } catch (JacksonException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not publish to " + topic, e);
        }
    }
}
