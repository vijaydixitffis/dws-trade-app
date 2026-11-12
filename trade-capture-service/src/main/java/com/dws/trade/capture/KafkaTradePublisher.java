package com.dws.trade.capture;

import com.dws.trade.event.Topics;
import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.Trade;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes with the trade id as the record key, so all versions of a trade share a partition. */
@Component
public class KafkaTradePublisher implements TradePublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public KafkaTradePublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Override
    public CompletableFuture<Void> inbound(Trade trade) {
        return send(Topics.INBOUND, trade.tradeId(), trade);
    }

    @Override
    public CompletableFuture<Void> rejected(TradeRejection rejection) {
        return send(Topics.REJECTED, rejection.trade().tradeId(), rejection);
    }

    private CompletableFuture<Void> send(String topic, String key, Object payload) {
        try {
            return kafka.send(topic, key, mapper.writeValueAsString(payload)).thenApply(result -> null);
        } catch (JsonProcessingException | RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
