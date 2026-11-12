package com.dws.trade.simulator;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** An administrator can cap how large a run may be, so the simulator cannot be used as an open load generator. */
@SpringBootTest(properties = "simulator.max-count=500")
@AutoConfigureMockMvc
class SimulatorLimitTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    CaptureClient capture;

    @Test
    void refusesARunAboveTheConfiguredCap() throws Exception {
        mvc.perform(post("/api/v1/simulations").with(httpBasic("admin", "admin@2026!"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":501,\"ratePerSecond\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("count must not exceed 500 on this installation"));
    }

    @Test
    void acceptsARunAtTheCap() throws Exception {
        mvc.perform(post("/api/v1/simulations").with(httpBasic("admin", "admin@2026!"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"count\":500,\"ratePerSecond\":1000}"))
                .andExpect(status().isAccepted());
    }
}
