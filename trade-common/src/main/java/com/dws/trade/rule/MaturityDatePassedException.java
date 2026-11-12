package com.dws.trade.rule;

import com.dws.trade.event.RejectionReason;
import java.time.LocalDate;

/** Rule 2: a trade arrived with a maturity date earlier than today. */
public class MaturityDatePassedException extends TradeRejectedException {

    private static final long serialVersionUID = 8461384326955214645L;

	public MaturityDatePassedException(String tradeId, LocalDate maturityDate, LocalDate today) {
        super(tradeId, RejectionReason.MATURITY_DATE_PASSED,
                "Trade %s maturity date %s is earlier than today %s".formatted(tradeId, maturityDate, today));
    }
}
