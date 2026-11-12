package com.dws.trade.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.dws.trade.model.Trade;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The generator must produce trades that really are what their scenario says. */
class TradeGeneratorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private static TradeGenerator generator(Mix mix) {
        return new TradeGenerator(mix, "RUN", TODAY, new Random(42));
    }

    @Test
    void newTradesStartAtVersionOneWithAFutureMaturityDate() {
        TradeGenerator generator = generator(new Mix(0, 0, 0, 0));

        for (int i = 0; i < 100; i++) {
            TradeGenerator.Generated g = generator.next();
            assertThat(g.scenario()).isEqualTo(Scenario.NEW_TRADE);
            assertThat(g.trade().version()).isEqualTo(1);
            assertThat(g.trade().maturityDate()).isAfter(TODAY);
            assertThat(g.trade().tradeId()).startsWith("RUN-");
        }
    }

    @Test
    void scenariosAreConsistentWithTheVersionsAlreadySent() {
        TradeGenerator generator = generator(new Mix(30, 15, 15, 10));
        Map<String, Integer> highest = new HashMap<>();

        for (int i = 0; i < 5000; i++) {
            TradeGenerator.Generated g = generator.next();
            Trade t = g.trade();
            Integer sent = highest.get(t.tradeId());
            switch (g.scenario()) {
                case NEW_TRADE -> assertThat(sent).isNull();
                case HIGHER_VERSION -> assertThat(t.version()).isGreaterThan(sent);
                case SAME_VERSION -> assertThat(t.version()).isEqualTo(sent);
                case LOWER_VERSION -> assertThat(t.version()).isLessThan(sent);
                case PAST_MATURITY -> assertThat(t.maturityDate()).isBefore(TODAY);
            }
            if (g.scenario() != Scenario.PAST_MATURITY) {
                assertThat(t.maturityDate()).isAfterOrEqualTo(TODAY);
                highest.merge(t.tradeId(), t.version(), Math::max);
            }
        }
    }

    @Test
    void producesRoughlyTheConfiguredMix() {
        TradeGenerator generator = generator(new Mix(20, 10, 10, 10));
        Map<Scenario, Integer> counts = new EnumMap<>(Scenario.class);
        int total = 20_000;

        for (int i = 0; i < total; i++) {
            counts.merge(generator.next().scenario(), 1, Integer::sum);
        }

        assertThat(counts.get(Scenario.PAST_MATURITY) * 100.0 / total).isCloseTo(10, within(2.0));
        assertThat(counts.get(Scenario.HIGHER_VERSION) * 100.0 / total).isCloseTo(20, within(2.0));
        assertThat(counts.get(Scenario.SAME_VERSION) * 100.0 / total).isCloseTo(10, within(2.0));
        assertThat(counts.get(Scenario.LOWER_VERSION) * 100.0 / total).isCloseTo(10, within(3.0));
    }
}
