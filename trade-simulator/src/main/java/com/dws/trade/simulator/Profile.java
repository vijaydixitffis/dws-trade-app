package com.dws.trade.simulator;

import java.util.List;

/**
 * The variety of the generated trades: which counter-parties and books are used and how far
 * ahead the maturity dates lie. Empty lists mean the built-in pools (CP-1 to CP-50, B1 to B10).
 */
public record Profile(List<String> counterParties, List<String> books, int minMaturityDays, int maxMaturityDays) {

    public static final int DEFAULT_MIN_DAYS = 1;
    public static final int DEFAULT_MAX_DAYS = 3650;

    public static Profile defaults() {
        return new Profile(List.of(), List.of(), DEFAULT_MIN_DAYS, DEFAULT_MAX_DAYS);
    }
}
