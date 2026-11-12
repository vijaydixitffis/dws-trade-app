package com.dws.trade.capture;

import com.dws.trade.event.RejectionReason;
import com.fasterxml.jackson.annotation.JsonInclude;

/** What the capture API did with one trade. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaptureResult(String tradeId, int version, Status status, RejectionReason reason, String message) {

    public enum Status {
        /** Published to the stream; the processor decides the final outcome. */
        ACCEPTED_FOR_PROCESSING,
        REJECTED
    }

    public boolean rejected() {
        return status == Status.REJECTED;
    }
}
