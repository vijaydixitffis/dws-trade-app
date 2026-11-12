package com.dws.trade.simulator;

import com.dws.trade.model.Trade;
import com.dws.trade.time.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Runs one simulation at a time on a background thread, pacing batches to the requested rate. */
@Service
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);
    /** Batches are sent ten times a second. */
    private static final long TICK_MILLIS = 100;
    private static final int MAX_BATCH = 1000;

    private final CaptureClient capture;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopRequested;
    private volatile SimulationStatus status = SimulationStatus.idle();

    public SimulationRunner(CaptureClient capture, @Value("${trade.business-zone:}") String zone) {
        this.capture = capture;
        this.clock = BusinessClock.forZone(zone);
    }

    public SimulationStatus status() {
        return status;
    }

    public void stop() {
        stopRequested = true;
    }

    /** @return false if a run is already in progress */
    public boolean start(SimulationRequest request) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        stopRequested = false;
        String runId = "S" + Long.toString(clock.millis(), 36).toUpperCase();
        status = new SimulationStatus(runId, true, request.count(), request.ratePerSecond(), 0, 0, 0, Map.of(),
                clock.instant(), null, null);
        Thread.ofVirtual().name("simulation-" + runId).start(() -> run(runId, request));
        return true;
    }

    private void run(String runId, SimulationRequest request) {
        TradeGenerator generator = new TradeGenerator(request.mix(), request.profile(), request.idPrefix(runId),
                LocalDate.now(clock), new Random());
        Map<Scenario, Long> byScenario = new EnumMap<>(Scenario.class);
        Instant startedAt = status.startedAt();
        long sent = 0;
        long accepted = 0;
        long rejected = 0;
        String error = null;
        int perTick = Math.clamp(Math.round(request.ratePerSecond() * TICK_MILLIS / 1000.0), 1, MAX_BATCH);
        try {
            while (sent < request.count() && !stopRequested) {
                long tickStart = System.nanoTime();
                int size = (int) Math.min(perTick, request.count() - sent);
                List<Trade> batch = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    TradeGenerator.Generated generated = generator.next();
                    byScenario.merge(generated.scenario(), 1L, Long::sum);
                    batch.add(generated.trade());
                }
                CaptureClient.Outcome outcome = capture.send(batch);
                sent += size;
                accepted += outcome.accepted();
                rejected += outcome.rejected();
                status = new SimulationStatus(runId, true, request.count(), request.ratePerSecond(), sent, accepted,
                        rejected, Map.copyOf(byScenario), startedAt, null, null);
                long sleep = TICK_MILLIS - (System.nanoTime() - tickStart) / 1_000_000;
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (RuntimeException e) {
            log.error("Simulation {} failed", runId, e);
            error = e.getMessage();
        } finally {
            status = new SimulationStatus(runId, false, request.count(), request.ratePerSecond(), sent, accepted,
                    rejected, Map.copyOf(byScenario), startedAt, clock.instant(), error);
            running.set(false);
        }
    }
}
