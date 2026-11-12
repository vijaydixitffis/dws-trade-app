package com.dws.trade.simulator;

import com.dws.trade.model.Trade;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates trades for one run. It remembers the highest version it has sent for each trade
 * so that "higher", "same" and "lower" versions are true relative to what the store has seen.
 * Not thread-safe; one run uses one generator from one thread.
 */
public class TradeGenerator {

    public record Generated(Scenario scenario, Trade trade) {
    }

    private static final int COUNTER_PARTIES = 50;
    private static final int BOOKS = 10;
    private static final int MAX_PAST_DAYS = 3650;

    private final Mix mix;
    private final Profile profile;
    private final String runId;
    private final LocalDate today;
    private final Random random;
    /** Highest version sent, indexed by trade sequence number. */
    private final List<Integer> highestVersion = new ArrayList<>();
    /** The trade chosen by {@link #pick()} for a lower-version send. */
    private int lowerVersionTarget = -1;

    public TradeGenerator(Mix mix, String runId, LocalDate today, Random random) {
        this(mix, Profile.defaults(), runId, today, random);
    }

    public TradeGenerator(Mix mix, Profile profile, String runId, LocalDate today, Random random) {
        this.mix = mix;
        this.profile = profile;
        this.runId = runId;
        this.today = today;
        this.random = random;
    }

    public Generated next() {
        Scenario scenario = pick();
        return switch (scenario) {
            case HIGHER_VERSION -> {
                int index = anyTrade();
                int version = highestVersion.get(index) + 1;
                highestVersion.set(index, version);
                yield new Generated(scenario, trade(index, version, futureDate()));
            }
            case SAME_VERSION -> {
                int index = anyTrade();
                yield new Generated(scenario, trade(index, highestVersion.get(index), futureDate()));
            }
            case LOWER_VERSION -> {
                int version = 1 + random.nextInt(highestVersion.get(lowerVersionTarget) - 1);
                yield new Generated(scenario, trade(lowerVersionTarget, version, futureDate()));
            }
            case PAST_MATURITY -> {
                // A fresh id that is never reused, so it does not disturb the version bookkeeping.
                String id = "%s-OLD-%d-%d".formatted(runId, highestVersion.size(), random.nextInt(1_000_000));
                yield new Generated(scenario, new Trade(id, 1, counterParty(), book(),
                        today.minusDays(1 + random.nextInt(MAX_PAST_DAYS))));
            }
            case NEW_TRADE -> {
                highestVersion.add(1);
                yield new Generated(scenario, trade(highestVersion.size() - 1, 1, futureDate()));
            }
        };
    }

    /** Picks by the configured mix, falling back to a new trade when a scenario is not yet possible. */
    private Scenario pick() {
        int roll = random.nextInt(100);
        int bound = mix.pastMaturityPct();
        if (roll < bound) {
            return Scenario.PAST_MATURITY;
        }
        if (highestVersion.isEmpty()) {
            return Scenario.NEW_TRADE;
        }
        bound += mix.higherVersionPct();
        if (roll < bound) {
            return Scenario.HIGHER_VERSION;
        }
        bound += mix.sameVersionPct();
        if (roll < bound) {
            return Scenario.SAME_VERSION;
        }
        bound += mix.lowerVersionPct();
        if (roll < bound) {
            lowerVersionTarget = tradeWithEarlierVersion();
            return lowerVersionTarget >= 0 ? Scenario.LOWER_VERSION : Scenario.HIGHER_VERSION;
        }
        return Scenario.NEW_TRADE;
    }

    private int anyTrade() {
        return random.nextInt(highestVersion.size());
    }

    /** A trade already sent at version 2 or above, or -1 if a few random probes find none. */
    private int tradeWithEarlierVersion() {
        for (int attempt = 0; attempt < 20; attempt++) {
            int index = anyTrade();
            if (highestVersion.get(index) > 1) {
                return index;
            }
        }
        return -1;
    }

    private Trade trade(int index, int version, LocalDate maturity) {
        return new Trade("%s-T%d".formatted(runId, index + 1), version, counterParty(), book(), maturity);
    }

    private LocalDate futureDate() {
        int span = profile.maxMaturityDays() - profile.minMaturityDays() + 1;
        return today.plusDays(profile.minMaturityDays() + random.nextInt(span));
    }

    private String counterParty() {
        return pick(profile.counterParties(), () -> "CP-" + (1 + random.nextInt(COUNTER_PARTIES)));
    }

    private String book() {
        return pick(profile.books(), () -> "B" + (1 + random.nextInt(BOOKS)));
    }

    private String pick(List<String> choices, java.util.function.Supplier<String> fallback) {
        return choices.isEmpty() ? fallback.get() : choices.get(random.nextInt(choices.size()));
    }
}
