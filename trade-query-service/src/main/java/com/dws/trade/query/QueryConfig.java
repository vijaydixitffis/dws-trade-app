package com.dws.trade.query;

import com.dws.trade.time.BusinessClock;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueryConfig {

    @Bean
    Clock businessClock(@Value("${trade.business-zone:}") String zone) {
        return BusinessClock.forZone(zone);
    }
}
