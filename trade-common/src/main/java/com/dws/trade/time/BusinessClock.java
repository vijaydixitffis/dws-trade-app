package com.dws.trade.time;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Supplies the clock that defines "today". By default this is the server's own date and
 * time zone; an administrator can set {@code trade.business-zone} to use another zone.
 */
public final class BusinessClock {

    private BusinessClock() {
    }

    public static Clock forZone(String configuredZone) {
        if (configuredZone == null || configuredZone.isBlank()) {
            return Clock.systemDefaultZone();
        }
        try {
            return Clock.system(ZoneId.of(configuredZone.trim()));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("trade.business-zone is not a valid time zone: " + configuredZone, e);
        }
    }
}
