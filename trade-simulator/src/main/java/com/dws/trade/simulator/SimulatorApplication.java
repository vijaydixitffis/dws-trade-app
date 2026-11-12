package com.dws.trade.simulator;

import org.springframework.boot.SpringApplication;
import com.dws.trade.security.TradeSecurityConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(TradeSecurityConfig.class)
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
