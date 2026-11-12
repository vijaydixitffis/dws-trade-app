package com.dws.trade.security;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Every API call needs the demo credentials; only the health check is open. */
@SpringBootTest(classes = SecurityTestApp.class, properties = "management.endpoints.web.exposure.include=health,info")
@AutoConfigureMockMvc
class TradeSecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @Test
    void refusesACallWithoutCredentialsAndAsksForBasicAuthentication() throws Exception {
        mvc.perform(get("/api/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
    }

    @Test
    void acceptsTheDemoAdministrator() throws Exception {
        mvc.perform(get("/api/v1/ping").with(httpBasic("admin", "admin@2026!")))
                .andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void refusesAWrongPasswordAndAnUnknownUser() throws Exception {
        mvc.perform(get("/api/v1/ping").with(httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ping").with(httpBasic("guest", "admin@2026!"))).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAnAuthenticatedPostWithoutACsrfToken() throws Exception {
        mvc.perform(post("/api/v1/ping").with(httpBasic("admin", "admin@2026!")))
                .andExpect(status().isOk());
    }

    @Test
    void refusesAPostWithoutCredentials() throws Exception {
        mvc.perform(post("/api/v1/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void leavesTheHealthCheckOpenForContainerProbes() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void protectsEveryOtherActuatorEndpoint() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info").with(httpBasic("admin", "admin@2026!"))).andExpect(status().isOk());
    }

    @Test
    void keepsNoServerSideSession() throws Exception {
        mvc.perform(get("/api/v1/ping").with(httpBasic("admin", "admin@2026!")))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
