package com.dws.trade.simulator;

import com.dws.trade.model.Trade;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Posts batches of trades to the capture API. */
@Component
public class CaptureClient {

    public record Outcome(long accepted, long rejected) {
    }

    private final RestClient http;

    public CaptureClient(RestClient.Builder builder, @Value("${simulator.capture-url}") String captureUrl) {
        this.http = builder.baseUrl(captureUrl).build();
    }

    public Outcome send(List<Trade> trades) {
        Outcome outcome = http.post().uri("/api/v1/trades/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body(trades)
                .retrieve()
                .body(Outcome.class);
        return outcome == null ? new Outcome(0, 0) : outcome;
    }
}
