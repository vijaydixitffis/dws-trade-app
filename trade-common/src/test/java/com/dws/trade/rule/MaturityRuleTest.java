package com.dws.trade.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dws.trade.event.RejectionReason;
import com.dws.trade.model.Trade;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Rule 2: the store rejects a trade whose maturity date is earlier than today. */
class MaturityRuleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static Trade tradeMaturing(LocalDate maturity) {
        return new Trade("T1", 1, "CP-1", "B1", maturity);
    }

    @Test
    void rejectsTradeThatMaturedYesterday() {
        Trade trade = tradeMaturing(TODAY.minusDays(1));

        assertThatThrownBy(() -> MaturityRule.check(trade, TODAY))
                .isInstanceOf(MaturityDatePassedException.class)
                .hasMessageContaining("T1")
                .hasMessageContaining("2026-11-11")
                .extracting(e -> ((TradeRejectedException) e).reason())
                .isEqualTo(RejectionReason.MATURITY_DATE_PASSED);
    }

    @Test
    void acceptsTradeMaturingToday() {
        assertThatCode(() -> MaturityRule.check(tradeMaturing(TODAY), TODAY)).doesNotThrowAnyException();
    }

    @Test
    void acceptsTradeMaturingInTheFuture() {
        assertThatCode(() -> MaturityRule.check(tradeMaturing(TODAY.plusYears(1)), TODAY)).doesNotThrowAnyException();
    }

    /** Rule 3 uses the same boundary: a trade is expired once its maturity date has passed. */
    @Test
    void maturityHasPassedOnlyOnTheDayAfter() {
        assertThat(MaturityRule.hasPassed(TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(MaturityRule.hasPassed(TODAY, TODAY)).isFalse();
        assertThat(MaturityRule.hasPassed(TODAY.plusDays(1), TODAY)).isFalse();
    }
}
