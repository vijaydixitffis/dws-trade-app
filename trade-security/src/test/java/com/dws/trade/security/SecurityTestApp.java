package com.dws.trade.security;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** A minimal service with the real services' paths, importing the shared security the way they do. */
@SpringBootApplication
@Import(TradeSecurityConfig.class)
@RestController
public class SecurityTestApp {

    @GetMapping("/api/v1/trades")
    String read() {
        return "read";
    }

    @PostMapping("/api/v1/trades")
    String write() {
        return "write";
    }

    @PostMapping("/api/v1/trades/batch")
    String writeBatch() {
        return "batch";
    }

    @GetMapping("/api/v1/simulations/current")
    String simulation() {
        return "simulation";
    }

    @DeleteMapping("/api/v1/simulations/current")
    String stopSimulation() {
        return "stopped";
    }

    @GetMapping("/api/v1/unlisted")
    String unlisted() {
        return "should never be reachable";
    }
}
