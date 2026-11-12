package com.dws.trade.simulator;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Parameters of a run. Percentages not given are zero; the remainder is new trades. */
public record SimulationRequest(
        @Min(1) @Max(10_000_000) int count,
        @Min(1) @Max(50_000) int ratePerSecond,
        @Min(0) @Max(100) int higherVersionPct,
        @Min(0) @Max(100) int sameVersionPct,
        @Min(0) @Max(100) int lowerVersionPct,
        @Min(0) @Max(100) int pastMaturityPct) {

    public Mix mix() {
        return new Mix(higherVersionPct, sameVersionPct, lowerVersionPct, pastMaturityPct);
    }

    @AssertTrue(message = "the scenario percentages must not add up to more than 100")
    public boolean isMixWithinOneHundred() {
        return mix().total() <= 100;
    }
}
