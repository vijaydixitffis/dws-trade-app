package com.dws.trade.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dws.trade.model.Trade;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Starting, watching and stopping a run through the simulator's REST API. */
@SpringBootTest
@AutoConfigureMockMvc
class SimulationControllerTest {

    @TestConfiguration
    static class SignedIn {
        /** Every request in this class signs in; authentication itself is tested in trade-security. */
        @Bean
        MockMvcBuilderCustomizer signedIn() {
            return builder -> builder.defaultRequest(get("/").with(httpBasic("admin", "admin@2026!")));
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SimulationRunner runner;
    @MockitoBean
    CaptureClient capture;

    private void captureAcceptsEverything(AtomicInteger sent) {
        when(capture.send(anyList())).thenAnswer(call -> {
            List<Trade> trades = call.getArgument(0);
            sent.addAndGet(trades.size());
            return new CaptureClient.Outcome(trades.size(), 0);
        });
    }

    @Test
    void runsTheRequestedNumberOfTradesAndReportsProgress() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        captureAcceptsEverything(sent);

        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON).content("""
                        {"count":250,"ratePerSecond":5000,"higherVersionPct":20,"sameVersionPct":10,
                         "lowerVersionPct":10,"pastMaturityPct":10}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requested").value(250));

        await().atMost(Duration.ofSeconds(20)).until(() -> !runner.status().running());
        assertThat(sent.get()).isEqualTo(250);
        mvc.perform(get("/api/v1/simulations/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(250))
                .andExpect(jsonPath("$.accepted").value(250))
                .andExpect(jsonPath("$.running").value(false));
    }

    @Test
    void stopsARunOnRequestAndRefusesASecondRunWhileOneIsActive() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        captureAcceptsEverything(sent);
        String slowRun = "{\"count\":100000,\"ratePerSecond\":100}";

        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON).content(slowRun))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON).content(slowRun))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/v1/simulations/current")).andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(10)).until(() -> !runner.status().running());
        assertThat(sent.get()).isLessThan(100000);
    }

    @Test
    void refusesARunWhoseMixExceedsOneHundredPercent() throws Exception {
        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON).content("""
                        {"count":10,"ratePerSecond":10,"higherVersionPct":60,"sameVersionPct":30,
                         "lowerVersionPct":20,"pastMaturityPct":0}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordsAFailureWhenTheCaptureApiCannotBeReached() throws Exception {
        when(capture.send(anyList())).thenThrow(new IllegalStateException("connection refused"));

        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":10,\"ratePerSecond\":1000}"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10)).until(() -> !runner.status().running());
        assertThat(runner.status().error()).contains("connection refused");
    }

    @Test
    void sendsTradesThatFollowTheDetailedVariationInputs() throws Exception {
        List<Trade> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(capture.send(anyList())).thenAnswer(call -> {
            List<Trade> trades = call.getArgument(0);
            received.addAll(trades);
            return new CaptureClient.Outcome(trades.size(), 0);
        });

        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON).content("""
                        {"count":100,"ratePerSecond":5000,"counterParties":["ACME","GLOBEX"],"books":["EQ-1","FX-2"],
                         "minMaturityDays":30,"maxMaturityDays":60,"tradeIdPrefix":"DEMO"}"""))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).until(() -> !runner.status().running());
        assertThat(received).hasSize(100).allSatisfy(t -> {
            assertThat(t.counterPartyId()).isIn("ACME", "GLOBEX");
            assertThat(t.bookId()).isIn("EQ-1", "FX-2");
            assertThat(t.tradeId()).startsWith("DEMO-");
        });
    }

    @Test
    void refusesAMaturityWindowWhoseEndIsBeforeItsStart() throws Exception {
        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":10,\"ratePerSecond\":10,\"minMaturityDays\":50,\"maxMaturityDays\":10}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesATradeIdPrefixWithUnsafeCharacters() throws Exception {
        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":10,\"ratePerSecond\":10,\"tradeIdPrefix\":\"a b/c\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesBlankCounterPartyAndBookNames() throws Exception {
        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":10,\"ratePerSecond\":10,\"counterParties\":[\"ACME\",\" \"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":10,\"ratePerSecond\":10,\"books\":[\"\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void neverSendsMoreThanOneThousandTradesInOneBatchEvenAtAHighRate() throws Exception {
        List<Integer> batchSizes = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(capture.send(anyList())).thenAnswer(call -> {
            List<Trade> trades = call.getArgument(0);
            batchSizes.add(trades.size());
            return new CaptureClient.Outcome(trades.size(), 0);
        });

        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":4000,\"ratePerSecond\":20000}"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).until(() -> !runner.status().running());
        assertThat(batchSizes).isNotEmpty().allSatisfy(size -> assertThat(size).isLessThanOrEqualTo(1000));
        assertThat(batchSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(4000);
    }

    @Test
    void honoursALowRateInsteadOfSendingAtLeastTenTradesASecond() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        captureAcceptsEverything(sent);
        long started = System.nanoTime();

        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":3,\"ratePerSecond\":2}"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).until(() -> !runner.status().running());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(sent.get()).isEqualTo(3);
        // Three trades at two a second take about 1.5 s; the old pacing finished in a tenth of that.
        assertThat(elapsedMillis).isBetween(1_000L, 6_000L);
    }

    @Test
    void keepsToTheRequestedRateOverAFewSeconds() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        captureAcceptsEverything(sent);
        long started = System.nanoTime();

        mvc.perform(post("/api/v1/simulations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":60,\"ratePerSecond\":20}"))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(20)).until(() -> !runner.status().running());
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(sent.get()).isEqualTo(60);
        assertThat(elapsedMillis).isBetween(2_500L, 6_000L);
    }
}
