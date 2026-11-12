package com.dws.trade.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** An administrator can replace the demo credentials through configuration. */
@SpringBootTest(classes = SecurityTestApp.class,
        properties = {"trade.security.username=ops", "trade.security.password=s3cret-value"})
@AutoConfigureMockMvc
class ConfiguredCredentialsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void usesTheConfiguredCredentialsInsteadOfTheDemoOnes() throws Exception {
        mvc.perform(get("/api/v1/ping").with(httpBasic("ops", "s3cret-value"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/ping").with(httpBasic("admin", "admin@2026!"))).andExpect(status().isUnauthorized());
    }
}
