package com.dws.trade.rule;

import com.dws.trade.event.RejectionReason;

/** Base class for the exceptions the store raises when it refuses a trade. */
public abstract class TradeRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;


    private final String tradeId;
    private final RejectionReason reason;

    protected TradeRejectedException(String tradeId, RejectionReason reason, String message) {
        super(message);
        this.tradeId = tradeId;
        this.reason = reason;
    }

    public String tradeId() {
        return tradeId;
    }

    public RejectionReason reason() {
        return reason;
    }
}
