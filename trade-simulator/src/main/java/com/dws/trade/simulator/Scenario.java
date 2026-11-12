package com.dws.trade.simulator;

/** The kinds of trade the simulator sends, named after what the store should do with them. */
public enum Scenario {
    /** First version of a new trade: accepted. */
    NEW_TRADE,
    /** A higher version of a trade already sent: accepted as a new row. */
    HIGHER_VERSION,
    /** The highest version sent so far, again: replaces the stored row. */
    SAME_VERSION,
    /** A version below the highest sent: rejected by the store. */
    LOWER_VERSION,
    /** Maturity date before today: rejected by the capture API. */
    PAST_MATURITY
}
