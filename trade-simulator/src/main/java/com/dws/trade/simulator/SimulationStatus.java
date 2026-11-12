package com.dws.trade.simulator;

import java.time.Instant;
import java.util.Map;

/** Progress of the current or most recent run. */
public record SimulationStatus(String runId, boolean running, int requested, int ratePerSecond, long sent,
        long accepted, long rejectedAtCapture, Map<Scenario, Long> byScenario, Instant startedAt, Instant finishedAt,
        String error) {

    public static SimulationStatus idle() {
        return new SimulationStatus(null, false, 0, 0, 0, 0, 0, Map.of(), null, null, null);
    }
}
