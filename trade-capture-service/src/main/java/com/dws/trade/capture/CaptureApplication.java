package com.dws.trade.capture;

import org.springframework.boot.SpringApplication;
import com.dws.trade.security.TradeSecurityConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(TradeSecurityConfig.class)
public class CaptureApplication {

    public static void main(String[] args) {
        SpringApplication.run(CaptureApplication.class, args);
    }
}
