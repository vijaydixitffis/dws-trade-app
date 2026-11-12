package com.dws.trade.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dws.trade.model.Trade;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The simulator signs in to the capture API with the configured credentials. */
class CaptureClientTest {

    @Test
    void sendsTheBatchWithBasicAuthenticationAndReadsTheCounts() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CaptureClient client = new CaptureClient(builder, "http://capture", "admin", "admin@2026!");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("admin:admin@2026!".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo("http://capture/api/v1/trades/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", expected))
                .andRespond(withSuccess("{\"accepted\":1,\"rejected\":0,\"results\":[]}", MediaType.APPLICATION_JSON));

        CaptureClient.Outcome outcome = client.send(List.of(new Trade("T1", 1, "CP-1", "B1", LocalDate.of(2030, 1, 1))));

        assertThat(outcome).isEqualTo(new CaptureClient.Outcome(1, 0));
        server.verify();
    }
}
