package com.dws.trade.security;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * HTTP Basic authentication with scope-based authorisation, for demonstration. It is shared by
 * every service and is deny-by-default: a request that matches no rule is refused.
 * <p>
 * Authentication proves who is calling (401 when it fails). Authorisation then checks the scope
 * the caller holds (403 when it lacks it): {@code trades:write} to post trades,
 * {@code trades:read} to read, {@code simulator:run} to drive the simulator and {@code ops:read}
 * for operational endpoints. Only the health check is open. Basic authentication sends the
 * password with every request and has no TLS here; the HLD describes the token-based design for
 * production, which keeps these same scope checks.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(TradeSecurityProperties.class)
public class TradeSecurityConfig {

    public static final String DEMO_USERNAME = "admin";
    public static final String DEMO_PASSWORD = "admin@2026!";

    private static final Logger log = LoggerFactory.getLogger(TradeSecurityConfig.class);

    @Bean
    SecurityFilterChain tradeSecurityFilterChain(HttpSecurity http, TradeSecurityProperties properties)
            throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasAuthority(Scopes.OPS_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/trades", "/api/v1/trades/**")
                        .hasAuthority(Scopes.TRADES_WRITE)
                        .requestMatchers("/api/v1/simulations", "/api/v1/simulations/**")
                        .hasAuthority(Scopes.SIMULATOR_RUN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAuthority(Scopes.TRADES_READ)
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                // No browser session or cookie exists to forge a request against.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new RequestSizeLimitFilter(properties.maxRequestBytes()), BasicAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(properties.rateLimitPerSecond(), System::nanoTime),
                        BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService tradeUsers(PasswordEncoder encoder, TradeSecurityProperties properties) {
        if (DEMO_PASSWORD.equals(properties.password())) {
            log.warn("Using the built-in demo password. Set TRADE_SECURITY_PASSWORD before any real use.");
        }
        List<UserDetails> users = new ArrayList<>();
        users.add(User.withUsername(properties.username()).password(encoder.encode(properties.password()))
                .authorities(Scopes.ALL).build());
        for (TradeSecurityProperties.Account account : properties.accounts()) {
            users.add(User.withUsername(account.username()).password(encoder.encode(account.password()))
                    .authorities(account.authorities().toArray(String[]::new)).build());
        }
        return new InMemoryUserDetailsManager(users);
    }
}
