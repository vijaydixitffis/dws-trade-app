package com.dws.trade.query;

import com.dws.trade.event.Topics;
import com.dws.trade.event.TradeEvent;
import com.dws.trade.event.TradeRejection;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Copies outcomes from Kafka into MongoDB and on to the live feed. */
@Component
public class Projector {

    private final ObjectMapper mapper;
    private final History history;
    private final LiveFeed liveFeed;

    public Projector(ObjectMapper mapper, History history, LiveFeed liveFeed) {
        this.mapper = mapper;
        this.history = history;
        this.liveFeed = liveFeed;
    }

    @KafkaListener(topics = Topics.EVENTS)
    public void onEvent(String json) throws JacksonException {
        TradeEvent event = mapper.readValue(json, TradeEvent.class);
        history.save(new EventDocument(event.eventId(), event.type(), event.trade(), event.at()));
        liveFeed.publish("trade-event", json);
    }

    @KafkaListener(topics = Topics.REJECTED)
    public void onRejection(String json) throws JacksonException {
        TradeRejection rejection = mapper.readValue(json, TradeRejection.class);
        history.save(new RejectionDocument(rejection.eventId(), rejection.reason(), rejection.message(),
                rejection.trade(), rejection.source(), rejection.at()));
        liveFeed.publish("trade-rejection", json);
    }
}
