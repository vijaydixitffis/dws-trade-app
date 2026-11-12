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

    @Test
    void listsTradesByTradeIdThenHighestVersionFirst() {
        List<StoredTrade> trades = repository.find(null, 50, 0);

        assertThat(trades).extracting(t -> t.tradeId() + " v" + t.version())
                .containsExactly("T1 v1", "T2 v2", "T2 v1", "T3 v3");
        assertThat(trades.get(0)).isEqualTo(new StoredTrade("T1", 1, "CP-1", "B1", MATURITY, CREATED, false));
        assertThat(trades.get(3).expired()).isTrue();
    }

    @Test
    void pagesThroughTheSequence() {
        assertThat(repository.find(null, 2, 2)).extracting(StoredTrade::tradeId).containsExactly("T2", "T3");
    }

    @Test
    void filtersByTradeIdPrefixIgnoringCase() {
        assertThat(repository.find("t2", 50, 0)).extracting(StoredTrade::version).containsExactly(2, 1);
        assertThat(repository.count("t2")).isEqualTo(2);
    }

    @Test
    void countsAllAndExpiredTrades() {
        assertThat(repository.count(null)).isEqualTo(4);
        assertThat(repository.countExpired()).isEqualTo(1);
    }
}
