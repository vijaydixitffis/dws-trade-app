package com.dws.trade.rule;

import com.dws.trade.event.RejectionReason;

/** Rule 1: a trade arrived with a version lower than the one already stored. */
public class LowerVersionTradeException extends TradeRejectedException {

    private static final long serialVersionUID = -2449872394491225523L;

	public LowerVersionTradeException(String tradeId, int incomingVersion, int storedVersion) {
        super(tradeId, RejectionReason.LOWER_VERSION,
                "Trade %s version %d is lower than the stored version %d"
                        .formatted(tradeId, incomingVersion, storedVersion));
    }
}
