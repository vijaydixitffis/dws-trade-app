package com.dws.trade.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dws.trade.model.StoredTrade;
import com.dws.trade.model.Trade;
import com.dws.trade.processor.store.StoreResult;
import com.dws.trade.processor.store.TradeStore;
import com.dws.trade.rule.LowerVersionTradeException;
import com.dws.trade.rule.MaturityDatePassedException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Rules 1 and 2 against a real PostgreSQL. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TradeStore.class)
class TradeStoreTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final LocalDate FUTURE = TODAY.plusYears(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
    }

    @Autowired
    TradeStore store;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyStore() {
        jdbc.update("TRUNCATE trade, trade_latest");
    }

    private static Trade trade(String id, int version) {
        return new Trade(id, version, "CP-1", "B1", FUTURE);
    }

    private List<Integer> versionsOf(String tradeId) {
        return jdbc.queryForList("SELECT version FROM trade WHERE trade_id = ? ORDER BY version", Integer.class, tradeId);
    }

    @Test
    void firstVersionOfATradeIsStoredUnexpiredWithTodayAsCreatedDate() {
        StoreResult result = store.save(trade("T1", 1), TODAY);

        assertThat(result.replaced()).isFalse();
        assertThat(result.trade()).isEqualTo(new StoredTrade("T1", 1, "CP-1", "B1", FUTURE, TODAY, false));
        assertThat(versionsOf("T1")).containsExactly(1);
        assertThat(jdbc.queryForObject("SELECT expired FROM trade WHERE trade_id = 'T1'", String.class)).isEqualTo("N");
    }

    @Test
    void higherVersionIsAddedAndEarlierVersionIsKept() {
        store.save(trade("T2", 1), TODAY);

        StoreResult result = store.save(trade("T2", 2), TODAY);

        assertThat(result.replaced()).isFalse();
        assertThat(versionsOf("T2")).containsExactly(1, 2);
    }

    @Test
    void sameVersionReplacesTheCurrentRecord() {
        store.save(trade("T2", 2), TODAY);
        LocalDate nextDay = TODAY.plusDays(1);

        StoreResult result = store.save(new Trade("T2", 2, "CP-9", "B9", FUTURE.plusDays(5)), nextDay);

        assertThat(result.replaced()).isTrue();
        assertThat(result.trade()).isEqualTo(new StoredTrade("T2", 2, "CP-9", "B9", FUTURE.plusDays(5), nextDay, false));
        assertThat(versionsOf("T2")).containsExactly(2);
        assertThat(jdbc.queryForObject("SELECT counter_party_id FROM trade WHERE trade_id = 'T2'", String.class))
                .isEqualTo("CP-9");
    }

    @Test
    void lowerVersionIsRejectedWithAnExceptionAndTheStoreIsUnchanged() {
        store.save(trade("T2", 2), TODAY);

        assertThatThrownBy(() -> store.save(new Trade("T2", 1, "CP-X", "BX", FUTURE), TODAY))
                .isInstanceOf(LowerVersionTradeException.class)
                .hasMessage("Trade T2 version 1 is lower than the stored version 2");

        assertThat(versionsOf("T2")).containsExactly(2);
    }

    @Test
    void versionBelowTheHighestIsRejectedEvenIfThatVersionWasNeverStored() {
        store.save(trade("T3", 1), TODAY);
        store.save(trade("T3", 3), TODAY);

        assertThatThrownBy(() -> store.save(trade("T3", 2), TODAY)).isInstanceOf(LowerVersionTradeException.class);

        assertThat(versionsOf("T3")).containsExactly(1, 3);
    }

    @Test
    void tradeWithMaturityDateBeforeTodayIsRejectedAndNothingIsStored() {
        Trade matured = new Trade("T4", 1, "CP-1", "B1", TODAY.minusDays(1));

        assertThatThrownBy(() -> store.save(matured, TODAY)).isInstanceOf(MaturityDatePassedException.class);

        assertThat(versionsOf("T4")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trade_latest WHERE trade_id = 'T4'", Integer.class)).isZero();
    }

    @Test
    void tradeMaturingTodayIsAccepted() {
        store.save(new Trade("T5", 1, "CP-1", "B1", TODAY), TODAY);

        assertThat(versionsOf("T5")).containsExactly(1);
    }

    @Test
    void concurrentWritersNeverLeaveAVersionAboveTheRecordedHighest() throws Exception {
        List<Integer> versions = new ArrayList<>();
        for (int v = 1; v <= 40; v++) {
            versions.add(v);
        }
        Collections.shuffle(versions);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int v : versions) {
                futures.add(pool.submit(() -> {
                    try {
                        store.save(trade("T-RACE", v), TODAY);
                        accepted.incrementAndGet();
                    } catch (LowerVersionTradeException e) {
                        rejected.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        List<Integer> stored = versionsOf("T-RACE");
        assertThat(accepted.get() + rejected.get()).isEqualTo(40);
        assertThat(stored).hasSize(accepted.get()).contains(40);
        assertThat(jdbc.queryForObject("SELECT latest_version FROM trade_latest WHERE trade_id = 'T-RACE'", Integer.class))
                .isEqualTo(40);
    }
}
