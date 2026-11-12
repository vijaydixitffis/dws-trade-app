package com.dws.trade.security;

/**
 * The permissions a caller can hold. They are named like OAuth 2.0 scopes so that moving from
 * HTTP Basic to token-based authentication changes only where they come from, not where they
 * are checked.
 */
public final class Scopes {

    /** Post trades to the capture API. */
    public static final String TRADES_WRITE = "trades:write";
    /** Read the trade store, history, summary and live feed. */
    public static final String TRADES_READ = "trades:read";
    /** Start and stop simulator runs. */
    public static final String SIMULATOR_RUN = "simulator:run";
    /** Read operational endpoints (metrics, info). */
    public static final String OPS_READ = "ops:read";

    public static final String[] ALL = {TRADES_WRITE, TRADES_READ, SIMULATOR_RUN, OPS_READ};

    private Scopes() {
    }
}
