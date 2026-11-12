package com.dws.trade.security;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** A minimal service that imports the shared security the way the real services do. */
@SpringBootApplication
@Import(TradeSecurityConfig.class)
@RestController
public class SecurityTestApp {

    @GetMapping("/api/v1/ping")
    String ping() {
        return "pong";
    }

    @PostMapping("/api/v1/ping")
    String post() {
        return "posted";
    }
}
