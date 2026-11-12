package com.dws.trade.processor.config;

import com.dws.trade.event.Topics;
import com.dws.trade.processor.OutcomePublisher;
import com.dws.trade.processor.expiry.ExpiryService;
import com.dws.trade.time.BusinessClock;
import tools.jackson.core.JacksonException;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ProcessorConfig {

    /** "Today" for the maturity and expiry rules: the server's zone unless overridden. */
    @Bean
    Clock businessClock(@Value("${trade.business-zone:}") String zone) {
        return BusinessClock.forZone(zone);
    }

    @Bean
    NewTopic inboundTopic(@Value("${trade.inbound-partitions}") int partitions) {
        return TopicBuilder.name(Topics.INBOUND).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic eventsTopic(@Value("${trade.inbound-partitions}") int partitions) {
        return TopicBuilder.name(Topics.EVENTS).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic rejectedTopic(@Value("${trade.inbound-partitions}") int partitions) {
        return TopicBuilder.name(Topics.REJECTED).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic deadLetterTopic() {
        return TopicBuilder.name(Topics.INBOUND_DLT).partitions(1).replicas(1).build();
    }

    /**
     * Records that can never succeed (unreadable, or violating a database constraint) are parked
     * on the dead-letter topic at once. Every other failure is treated as an outage of a
     * dependency: it is retried with exponential back-off for up to {@code max-elapsed-ms}, which
     * holds the partition, so a database restart delays trades instead of dead-lettering them.
     */
    @Bean
    CommonErrorHandler errorHandler(KafkaOperations<String, String> kafka,
            @Value("${trade.retry.initial-interval-ms}") long initialIntervalMs,
            @Value("${trade.retry.max-interval-ms}") long maxIntervalMs,
            @Value("${trade.retry.max-elapsed-ms}") long maxElapsedMs) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(Topics.INBOUND_DLT, 0));
        ExponentialBackOff backOff = new ExponentialBackOff(initialIntervalMs, 2.0);
        backOff.setMaxInterval(maxIntervalMs);
        backOff.setMaxElapsedTime(maxElapsedMs);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(JacksonException.class, IllegalArgumentException.class,
                DataIntegrityViolationException.class);
        return handler;
    }

    @Bean
    ExpiryService expiryService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            OutcomePublisher publisher, Clock clock, @Value("${trade.expiry.batch-size}") int batchSize) {
        return new ExpiryService(jdbc, new TransactionTemplate(transactionManager), publisher, clock, batchSize);
    }

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
