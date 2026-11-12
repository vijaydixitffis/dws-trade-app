package com.dws.trade.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Who is calling is not enough: each caller may use only the scopes it was given. Everything else is refused. */
@SpringBootTest(classes = SecurityTestApp.class, properties = {
        "management.endpoints.web.exposure.include=health,info",
        "trade.security.accounts[0].username=trader",
        "trade.security.accounts[0].password=trader-pw",
        "trade.security.accounts[0].authorities=trades:write",
        "trade.security.accounts[1].username=viewer",
        "trade.security.accounts[1].password=viewer-pw",
        "trade.security.accounts[1].authorities=trades:read",
        "trade.security.accounts[2].username=tester",
        "trade.security.accounts[2].password=tester-pw",
        "trade.security.accounts[2].authorities=simulator:run",
        "trade.security.accounts[3].username=monitor",
        "trade.security.accounts[3].password=monitor-pw",
        "trade.security.accounts[3].authorities=ops:read"})
@AutoConfigureMockMvc
class AuthorisationTest {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor as(String user, String password) {
        return httpBasic(user, password);
    }

    @Test
    void aWriterMayPostTradesAndNothingElse() throws Exception {
        RequestPostProcessor trader = as("trader", "trader-pw");

        mvc.perform(post("/api/v1/trades").with(trader)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/trades/batch").with(trader)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/trades").with(trader)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/simulations/current").with(trader)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").with(trader)).andExpect(status().isForbidden());
    }

    @Test
    void aReaderMayReadTradesAndNothingElse() throws Exception {
        RequestPostProcessor viewer = as("viewer", "viewer-pw");

        mvc.perform(get("/api/v1/trades").with(viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/trades").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/simulations/current").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/simulations/current").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").with(viewer)).andExpect(status().isForbidden());
    }

    @Test
    void aTesterMayRunTheSimulatorButNotTouchTrades() throws Exception {
        RequestPostProcessor tester = as("tester", "tester-pw");

        mvc.perform(get("/api/v1/simulations/current").with(tester)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/simulations/current").with(tester)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/trades").with(tester)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trades").with(tester)).andExpect(status().isForbidden());
    }

    @Test
    void aMonitorMayReadOperationalEndpointsOnly() throws Exception {
        RequestPostProcessor monitor = as("monitor", "monitor-pw");

        mvc.perform(get("/actuator/info").with(monitor)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/trades").with(monitor)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/trades").with(monitor)).andExpect(status().isForbidden());
    }

    @Test
    void theAdministratorHoldsEveryScope() throws Exception {
        RequestPostProcessor admin = as("admin", "admin@2026!");

        mvc.perform(get("/api/v1/trades").with(admin)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/trades").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/simulations/current").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/actuator/info").with(admin)).andExpect(status().isOk());
    }

    @Test
    void anythingNotListedIsRefusedEvenForTheAdministrator() throws Exception {
        mvc.perform(get("/api/v1/unlisted").with(as("admin", "admin@2026!"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/unlisted")).andExpect(status().isUnauthorized());
    }

    @Test
    void theHealthCheckStaysOpen() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void aMethodNobodyMayUseIsRefused() throws Exception {
        mvc.perform(delete("/api/v1/trades").with(as("admin", "admin@2026!"))).andExpect(status().isForbidden());
    }
}
