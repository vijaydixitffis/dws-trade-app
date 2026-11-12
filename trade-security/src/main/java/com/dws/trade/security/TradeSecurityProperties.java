package com.dws.trade.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code trade.security}. The administrator account holds every scope; further
 * accounts, each with only the scopes listed for it, can be added under {@code accounts}.
 *
 * @param username            the administrator's user name
 * @param password            the administrator's password (demo default; set it for any real use)
 * @param accounts            additional accounts with limited scopes
 * @param rateLimitPerSecond  requests each caller may make per second; 0 switches the limit off
 * @param maxRequestBytes     the largest request body accepted
 */
@ConfigurationProperties("trade.security")
public record TradeSecurityProperties(
        @DefaultValue("admin") String username,
        @DefaultValue("admin@2026!") String password,
        List<Account> accounts,
        @DefaultValue("600") int rateLimitPerSecond,
        @DefaultValue("2097152") long maxRequestBytes) {

    public TradeSecurityProperties {
        accounts = accounts == null ? List.of() : accounts;
    }

    /** An account limited to the scopes named in {@code authorities}, for example {@code trades:write}. */
    public record Account(String username, String password, List<String> authorities) {
    }
}
