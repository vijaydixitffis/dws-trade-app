package com.dws.trade.simulator;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Parameters of a run. Percentages not given are zero; the remainder is new trades. The last
 * five fields are optional and shape the variety of the trades: counter-party and book pools,
 * how many days ahead maturity dates fall, and the prefix of the generated trade ids.
 */
public record SimulationRequest(
        @Min(1) @Max(10_000_000) int count,
        @Min(1) @Max(50_000) int ratePerSecond,
        @Min(0) @Max(100) int higherVersionPct,
        @Min(0) @Max(100) int sameVersionPct,
        @Min(0) @Max(100) int lowerVersionPct,
        @Min(0) @Max(100) int pastMaturityPct,
        @Size(max = 100) List<@NotBlank @Size(max = 32) String> counterParties,
        @Size(max = 100) List<@NotBlank @Size(max = 32) String> books,
        @Min(0) @Max(3650) Integer minMaturityDays,
        @Min(0) @Max(3650) Integer maxMaturityDays,
        @Pattern(regexp = "[A-Za-z0-9-]{1,16}") String tradeIdPrefix) {

    public Profile profile() {
        return new Profile(counterParties == null ? List.of() : counterParties,
                books == null ? List.of() : books,
                minMaturityDays == null ? Profile.DEFAULT_MIN_DAYS : minMaturityDays,
                maxMaturityDays == null ? Profile.DEFAULT_MAX_DAYS : maxMaturityDays);
    }

    /** The prefix the user chose, or the run id when none was given. */
    public String idPrefix(String runId) {
        return tradeIdPrefix == null || tradeIdPrefix.isBlank() ? runId : tradeIdPrefix;
    }

    @AssertTrue(message = "the maturity window must not end before it starts")
    public boolean isMaturityWindowOrdered() {
        Profile p = profile();
        return p.minMaturityDays() <= p.maxMaturityDays();
    }

    public Mix mix() {
        return new Mix(higherVersionPct, sameVersionPct, lowerVersionPct, pastMaturityPct);
    }

    @AssertTrue(message = "the scenario percentages must not add up to more than 100")
    public boolean isMixWithinOneHundred() {
        return mix().total() <= 100;
    }
}
