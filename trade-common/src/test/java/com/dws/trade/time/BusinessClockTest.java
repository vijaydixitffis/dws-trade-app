package com.dws.trade.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** "Today" is the server's date unless an administrator configures another zone. */
class BusinessClockTest {

    @Test
    void usesServerZoneWhenNothingIsConfigured() {
        assertThat(BusinessClock.forZone(null).getZone()).isEqualTo(ZoneId.systemDefault());
        assertThat(BusinessClock.forZone("").getZone()).isEqualTo(ZoneId.systemDefault());
        assertThat(BusinessClock.forZone("  ").getZone()).isEqualTo(ZoneId.systemDefault());
    }

    @Test
    void usesConfiguredZone() {
        assertThat(BusinessClock.forZone("Asia/Tokyo").getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void failsFastOnUnknownZone() {
        assertThatThrownBy(() -> BusinessClock.forZone("Mars/Olympus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mars/Olympus");
    }
}
