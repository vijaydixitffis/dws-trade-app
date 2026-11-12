package com.dws.trade.processor;

import com.dws.trade.event.Topics;
import com.dws.trade.model.Trade;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the inbound topic. Records are keyed by trade id, so all versions of a trade are
 * handled in order by one consumer thread. The offset is committed only after the listener
 * returns, that is after the store has committed and the outcome has been published.
 */
@Component
public class TradeListener {

    private final TradeProcessor processor;
    private final ObjectMapper mapper;
    private final Validator validator;

    public TradeListener(TradeProcessor processor, ObjectMapper mapper, Validator validator) {
        this.processor = processor;
        this.mapper = mapper;
        this.validator = validator;
    }

    @KafkaListener(topics = Topics.INBOUND, concurrency = "${trade.processor.concurrency}")
    public void onTrade(ConsumerRecord<String, String> record) throws JacksonException {
        Trade trade = mapper.readValue(record.value(), Trade.class);
        String eventId = "%s-%d-%d".formatted(record.topic(), record.partition(), record.offset());
        Set<ConstraintViolation<Trade>> violations = validator.validate(trade);
        if (!violations.isEmpty()) {
            // Other producers may write to the topic. A readable but invalid trade is a business
            // rejection with a recorded reason; only unreadable records go to the dead-letter topic.
            processor.rejectInvalid(trade, eventId, "Invalid trade: " + violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().collect(Collectors.joining(", ")));
            return;
        }
        processor.process(trade, eventId);
    }
}
