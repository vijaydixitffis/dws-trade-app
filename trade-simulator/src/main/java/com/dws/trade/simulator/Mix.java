package com.dws.trade.simulator;

/** Percentage of each non-default scenario; the remainder is new trades. */
public record Mix(int higherVersionPct, int sameVersionPct, int lowerVersionPct, int pastMaturityPct) {

    public int total() {
        return higherVersionPct + sameVersionPct + lowerVersionPct + pastMaturityPct;
    }
}
