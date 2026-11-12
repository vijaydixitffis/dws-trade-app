package com.dws.trade.capture;

import com.dws.trade.model.Trade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trades")
public class CaptureController {

    static final int MAX_BATCH = 1000;

    private final CaptureService service;

    public CaptureController(CaptureService service) {
        this.service = service;
    }

    /** 202 when the trade is on the stream; 422 when its maturity date is earlier than today. */
    @PostMapping
    public ResponseEntity<CaptureResult> capture(@Valid @RequestBody Trade trade) {
        CaptureResult result = service.capture(trade);
        HttpStatus status = result.rejected() ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(result);
    }

    /** 202 with one result per trade, in request order. */
    @PostMapping("/batch")
    public ResponseEntity<BatchResult> captureBatch(
            @RequestBody @NotEmpty @Size(max = MAX_BATCH) List<@Valid Trade> trades) {
        return ResponseEntity.accepted().body(BatchResult.of(service.captureAll(trades)));
    }

    @ExceptionHandler(StreamUnavailableException.class)
    public ProblemDetail streamUnavailable(StreamUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
