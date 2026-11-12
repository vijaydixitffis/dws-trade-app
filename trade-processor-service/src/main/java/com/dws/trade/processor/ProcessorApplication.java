package com.dws.trade.processor;

import org.springframework.boot.SpringApplication;
import com.dws.trade.security.TradeSecurityConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(TradeSecurityConfig.class)
public class ProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessorApplication.class, args);
    }
}
