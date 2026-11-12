package com.dws.trade.processor;

import com.dws.trade.event.Topics;
import com.dws.trade.event.TradeRejection;
import com.dws.trade.processor.outbox.OutboundMessage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes to Kafka and waits for the broker to acknowledge. A failure fails the caller, which
 * is retried; reprocessing a trade is harmless.
 */
@Component
public class KafkaOutcomePublisher implements OutcomePublisher {

    private static final long SEND_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public KafkaOutcomePublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Override
    public void rejection(TradeRejection rejection) {
        sendAll(List.of(new OutboundMessage(Topics.REJECTED, rejection.trade().tradeId(),
                mapper.writeValueAsString(rejection))));
    }

    @Override
    public void sendAll(List<OutboundMessage> messages) {
        // Send everything first and wait once; the idempotent producer keeps the order per partition.
        CompletableFuture<?>[] pending = messages.stream()
                .map(m -> kafka.send(m.topic(), m.key(), m.payload()))
                .toArray(CompletableFuture<?>[]::new);
        try {
            CompletableFuture.allOf(pending).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not publish " + messages.size() + " message(s)", e);
        }
    }
}
