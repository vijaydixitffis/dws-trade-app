package com.dws.trade.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.event.TradeRejection;
import com.dws.trade.model.Trade;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of the capture API, with "today" fixed at 2026-11-12. */
@WebMvcTest(CaptureController.class)
@Import({CaptureService.class, CaptureControllerTest.FixedClock.class})
class CaptureControllerTest {

    private static final Instant NOW = Instant.parse("2026-11-12T09:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneId.of("UTC"));
        }

        /** Every request in this class signs in; authentication itself is tested in trade-security. */
        @Bean
        MockMvcBuilderCustomizer signedIn() {
            return builder -> builder.defaultRequest(post("/").with(httpBasic("admin", "admin@2026!")));
        }
    }

    @Autowired
    MockMvc mvc;
    @MockitoBean
    TradePublisher publisher;

    @BeforeEach
    void publisherSucceeds() {
        when(publisher.inbound(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(publisher.rejected(any())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static String json(String id, int version, String maturity) {
        return """
                {"tradeId":"%s","version":%d,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"%s"}"""
                .formatted(id, version, maturity);
    }

    @Test
    void acceptsValidTradeWith202AndPublishesItForProcessing() throws Exception {
        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON).content(json("T1", 1, "2027-05-20")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.tradeId").value("T1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED_FOR_PROCESSING"));

        verify(publisher).inbound(new Trade("T1", 1, "CP-1", "B1", LocalDate.of(2027, 5, 20)));
        verify(publisher, never()).rejected(any());
    }

    @Test
    void acceptsTradeMaturingToday() throws Exception {
        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON).content(json("T1", 1, "2026-11-12")))
                .andExpect(status().isAccepted());
    }

    @Test
    void rejectsPastMaturityWith422AndRecordsTheRejection() throws Exception {
        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON).content(json("T3", 3, "2026-11-11")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("MATURITY_DATE_PASSED"))
                .andExpect(jsonPath("$.message").value("Trade T3 maturity date 2026-11-11 is earlier than today 2026-11-12"));

        ArgumentCaptor<TradeRejection> rejection = ArgumentCaptor.forClass(TradeRejection.class);
        verify(publisher).rejected(rejection.capture());
        verify(publisher, never()).inbound(any());
        assertThat(rejection.getValue().reason()).isEqualTo(RejectionReason.MATURITY_DATE_PASSED);
        assertThat(rejection.getValue().source()).isEqualTo(TradeRejection.SOURCE_CAPTURE);
        assertThat(rejection.getValue().eventId()).startsWith("capture-");
        assertThat(rejection.getValue().at()).isEqualTo(NOW);
    }

    @Test
    void refusesIncompleteTradeWith400AndPublishesNothing() throws Exception {
        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tradeId\":\"\",\"version\":0,\"maturityDate\":\"2027-05-20\"}"))
                .andExpect(status().isBadRequest());

        verify(publisher, never()).inbound(any());
        verify(publisher, never()).rejected(any());
    }

    @Test
    void batchReportsEachTradeAndCountsBothOutcomes() throws Exception {
        String batch = "[" + json("T1", 1, "2027-05-20") + "," + json("T3", 3, "2020-05-20") + ","
                + json("T2", 2, "2027-05-20") + "]";

        mvc.perform(post("/api/v1/trades/batch").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.results[1].tradeId").value("T3"))
                .andExpect(jsonPath("$.results[1].status").value("REJECTED"));
    }

    @Test
    void batchWithAnIncompleteTradeIsRefusedWith400() throws Exception {
        mvc.perform(post("/api/v1/trades/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + json("T1", 1, "2027-05-20") + ",{\"tradeId\":\"T9\"}]"))
                .andExpect(status().isBadRequest());

        verify(publisher, never()).inbound(any());
    }

    @Test
    void answers503WhenTheStreamCannotBeReached() throws Exception {
        when(publisher.inbound(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        mvc.perform(post("/api/v1/trades").contentType(MediaType.APPLICATION_JSON).content(json("T1", 1, "2027-05-20")))
                .andExpect(status().isServiceUnavailable());
    }
}
