package com.dws.trade.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.dws.trade.event.RejectionReason;
import org.junit.jupiter.api.Test;

/** Rule 1: a lower version is rejected with an exception that says why. */
class LowerVersionTradeExceptionTest {

    @Test
    void carriesReasonAndBothVersions() {
        LowerVersionTradeException e = new LowerVersionTradeException("T2", 1, 2);

        assertThat(e.reason()).isEqualTo(RejectionReason.LOWER_VERSION);
        assertThat(e.tradeId()).isEqualTo("T2");
        assertThat(e).hasMessage("Trade T2 version 1 is lower than the stored version 2");
    }
}
