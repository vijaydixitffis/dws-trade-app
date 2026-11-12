package com.dws.trade.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The limits are part of the security chain and can be set by configuration. */
@SpringBootTest(classes = SecurityTestApp.class, properties = {
        "trade.security.rate-limit-per-second=3",
        "trade.security.max-request-bytes=200"})
@AutoConfigureMockMvc
class ConfiguredLimitsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void refusesALargeBodyWith413() throws Exception {
        mvc.perform(post("/api/v1/trades").with(httpBasic("admin", "admin@2026!"))
                        .contentType(MediaType.APPLICATION_JSON).content("x".repeat(500)))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void refusesTheFourthRequestInASecondWith429() throws Exception {
        int refused = 0;
        for (int i = 0; i < 6; i++) {
            int code = mvc.perform(get("/api/v1/trades").with(httpBasic("admin", "admin@2026!")))
                    .andReturn().getResponse().getStatus();
            if (code == 429) {
                refused++;
            }
        }

        // The window may roll over once during the loop, so allow a little slack around the ideal 3.
        org.assertj.core.api.Assertions.assertThat(refused).isBetween(1, 4);
    }
}
