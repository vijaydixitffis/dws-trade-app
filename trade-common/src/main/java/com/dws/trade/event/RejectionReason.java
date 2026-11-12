package com.dws.trade.event;

public enum RejectionReason {
    LOWER_VERSION,
    MATURITY_DATE_PASSED,
    /** The trade was readable but failed validation, for example a missing field or an id that is too long. */
    INVALID_TRADE
}
