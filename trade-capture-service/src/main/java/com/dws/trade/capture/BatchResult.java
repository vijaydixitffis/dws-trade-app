package com.dws.trade.capture;

import java.util.List;

public record BatchResult(long accepted, long rejected, List<CaptureResult> results) {

    public static BatchResult of(List<CaptureResult> results) {
        long rejected = results.stream().filter(CaptureResult::rejected).count();
        return new BatchResult(results.size() - rejected, rejected, results);
    }
}
