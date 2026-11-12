package com.dws.trade.capture;

import com.dws.trade.event.Topics;
import com.dws.trade.time.BusinessClock;
import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class CaptureConfig {

    /** "Today" for the maturity rule: the server's zone unless overridden. */
    @Bean
    Clock businessClock(@Value("${trade.business-zone:}") String zone) {
        return BusinessClock.forZone(zone);
    }

    // Declared here as well as in the processor so the topics exist with the right partition
    // count whichever service starts first.
    @Bean
    NewTopic inboundTopic(@Value("${trade.inbound-partitions}") int partitions) {
        return TopicBuilder.name(Topics.INBOUND).partitions(partitions).replicas(1).build();
    }

    @Bean
    NewTopic rejectedTopic(@Value("${trade.inbound-partitions}") int partitions) {
        return TopicBuilder.name(Topics.REJECTED).partitions(partitions).replicas(1).build();
    }
}
