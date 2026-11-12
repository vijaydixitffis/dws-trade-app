package com.dws.trade.query;

import com.dws.trade.event.Topics;
import com.dws.trade.time.BusinessClock;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

@Configuration
public class QueryConfig {

    @Bean
    Clock businessClock(@Value("${trade.business-zone:}") String zone) {
        return BusinessClock.forZone(zone);
    }

    @Bean
    NewTopic eventsDeadLetterTopic() {
        return TopicBuilder.name(Topics.EVENTS + ".DLT").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic rejectedDeadLetterTopic() {
        return TopicBuilder.name(Topics.REJECTED + ".DLT").partitions(1).replicas(1).build();
    }

    /**
     * Unreadable messages are parked on a dead-letter topic at once. Anything else, such as
     * MongoDB being unreachable, is retried with exponential back-off so no event is skipped
     * and the history catches up when the dependency returns.
     */
    @Bean
    CommonErrorHandler errorHandler(KafkaOperations<String, String> kafka,
            @Value("${trade.retry.initial-interval-ms}") long initialIntervalMs,
            @Value("${trade.retry.max-interval-ms}") long maxIntervalMs,
            @Value("${trade.retry.max-elapsed-ms}") long maxElapsedMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", 0));
        ExponentialBackOff backOff = new ExponentialBackOff(initialIntervalMs, 2.0);
        backOff.setMaxInterval(maxIntervalMs);
        backOff.setMaxElapsedTime(maxElapsedMs);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class);
        return handler;
    }
}
