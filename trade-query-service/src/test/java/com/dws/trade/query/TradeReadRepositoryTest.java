package com.dws.trade.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.dws.trade.model.StoredTrade;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The store is read back in its defined sequence: trade id ascending, then version descending. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TradeReadRepository.class)
class TradeReadRepositoryTest {

    private static final LocalDate MATURITY = LocalDate.of(2027, 5, 20);
    private static final LocalDate CREATED = LocalDate.of(2026, 11, 12);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.postgres(registry);
    }

    @Autowired
    TradeReadRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void sampleStore() {
        jdbc.update("TRUNCATE trade, trade_latest");
        insert("T2", 1, "N");
        insert("T3", 3, "Y");
        insert("T1", 1, "N");
        insert("T2", 2, "N");
    }

    private void insert(String id, int version, String expired) {
        jdbc.update("INSERT INTO trade VALUES (?, ?, 'CP-1', 'B1', ?, ?, ?)", id, version, MATURITY, CREATED, expired);
    }

    private static List<String> keys(List<StoredTrade> trades) {
        return trades.stream().map(t -> t.tradeId() + " v" + t.version()).toList();
    }

    @Test
    void listsTradesByTradeIdThenHighestVersionFirst() {
        List<StoredTrade> trades = repository.find(null, null, 50);

        assertThat(keys(trades)).containsExactly("T1 v1", "T2 v2", "T2 v1", "T3 v3");
        assertThat(trades.get(0)).isEqualTo(new StoredTrade("T1", 1, "CP-1", "B1", MATURITY, CREATED, false));
        assertThat(trades.get(3).expired()).isTrue();
    }

    @Test
    void continuesAfterACursorWithoutSkippingOrRepeatingRows() {
        assertThat(keys(repository.find(null, null, 3))).containsExactly("T1 v1", "T2 v2", "T2 v1");
        assertThat(keys(repository.find(null, new Cursor("T2", 1), 3))).containsExactly("T3 v3");
        // A cursor in the middle of one trade's versions keeps the lower versions of that trade.
        assertThat(keys(repository.find(null, new Cursor("T2", 2), 3))).containsExactly("T2 v1", "T3 v3");
        assertThat(repository.find(null, new Cursor("T3", 3), 3)).isEmpty();
    }

    @Test
    void filtersByACaseSensitiveTradeIdPrefix() {
        assertThat(keys(repository.find("T2", null, 50))).containsExactly("T2 v2", "T2 v1");
        assertThat(repository.find("t2", null, 50)).isEmpty();
        assertThat(repository.find("T", null, 50)).hasSize(4);
    }

    @Test
    void treatsUnderscoreAndPercentInThePrefixAsPlainCharacters() {
        assertThat(repository.find("T_", null, 50)).isEmpty();
        assertThat(repository.find("T%", null, 50)).isEmpty();
    }

    @Test
    void combinesAPrefixWithACursor() {
        assertThat(keys(repository.find("T", new Cursor("T1", 1), 50))).containsExactly("T2 v2", "T2 v1", "T3 v3");
    }

    @Test
    void countsExactlyBelowTheCapAndStopsCountingAboveIt() {
        assertThat(repository.count(null)).isEqualTo(new TradeReadRepository.Count(4, true));
        assertThat(repository.count("T2")).isEqualTo(new TradeReadRepository.Count(2, true));

        jdbc.update("INSERT INTO trade SELECT 'BULK' || g, 1, 'CP-1', 'B1', ?, ?, 'N' FROM generate_series(1, 10500) g",
                MATURITY, CREATED);

        assertThat(repository.count(null)).isEqualTo(new TradeReadRepository.Count(TradeReadRepository.COUNT_CAP, false));
        assertThat(repository.count("T2")).isEqualTo(new TradeReadRepository.Count(2, true));
        assertThat(repository.countExact()).isEqualTo(10504);
    }

    @Test
    void countsExpiredTrades() {
        assertThat(repository.countExpired()).isEqualTo(1);
    }

    @Test
    void everyKindOfPageIsServedFromTheSequenceIndex() {
        jdbc.update("INSERT INTO trade SELECT 'ID' || lpad(g::text, 7, '0'), 1 + g % 3, 'CP-1', 'B1', ?, ?, 'N' "
                + "FROM generate_series(1, 20000) g", MATURITY, CREATED);
        jdbc.execute("ANALYZE trade");

        for (boolean prefix : new boolean[] {false, true}) {
            for (boolean cursor : new boolean[] {false, true}) {
                List<Object> params = new java.util.ArrayList<>();
                if (prefix) {
                    params.add("ID000");
                    params.add("ID001");
                }
                if (cursor) {
                    params.add("ID0000500");
                    params.add("ID0000500");
                    params.add(2);
                }
                params.add(26);
                String plan = String.join("\n", jdbc.queryForList("EXPLAIN " + TradeReadRepository.findSql(prefix, cursor),
                        String.class, params.toArray()));

                assertThat(plan).as("prefix=%s cursor=%s", prefix, cursor)
                        .contains("trade_sequence_idx").doesNotContain("Seq Scan").doesNotContain("Sort");
            }
        }
    }
}
