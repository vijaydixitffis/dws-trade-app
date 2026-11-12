package com.dws.trade.rule;

import com.dws.trade.model.Trade;
import java.time.LocalDate;

/** The maturity boundary used by rule 2 (reject on arrival) and rule 3 (expire later). */
public final class MaturityRule {

    private MaturityRule() {
    }

    /** A maturity date has passed when it is earlier than today; maturing today has not passed. */
    public static boolean hasPassed(LocalDate maturityDate, LocalDate today) {
        return maturityDate.isBefore(today);
    }

    /** @throws MaturityDatePassedException if the trade's maturity date is earlier than today */
    public static void check(Trade trade, LocalDate today) {
        if (hasPassed(trade.maturityDate(), today)) {
            throw new MaturityDatePassedException(trade.tradeId(), trade.maturityDate(), today);
        }
    }
}
