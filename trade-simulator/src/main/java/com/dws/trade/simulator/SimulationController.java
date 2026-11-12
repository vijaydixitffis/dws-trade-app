package com.dws.trade.simulator;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulations")
public class SimulationController {

    private final SimulationRunner runner;

    public SimulationController(SimulationRunner runner) {
        this.runner = runner;
    }

    /** 202 when the run has started; 409 when another run is still in progress. */
    @PostMapping
    public ResponseEntity<SimulationStatus> start(@Valid @RequestBody SimulationRequest request) {
        HttpStatus code = runner.start(request) ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT;
        return ResponseEntity.status(code).body(runner.status());
    }

    @GetMapping("/current")
    public SimulationStatus current() {
        return runner.status();
    }

    @DeleteMapping("/current")
    public SimulationStatus stop() {
        runner.stop();
        return runner.status();
    }
}
