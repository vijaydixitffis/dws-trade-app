package com.dws.trade.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic authentication with one administrator account, for demonstration only.
 * <p>
 * Every request must carry the credentials, except the health check that container probes
 * call. The account comes from {@code trade.security.username} and
 * {@code trade.security.password} (environment variables {@code TRADE_SECURITY_USERNAME}
 * and {@code TRADE_SECURITY_PASSWORD}). Basic authentication sends the password with every
 * request and has no roles; see the HLD for the design recommended for production.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TradeSecurityConfig {

    public static final String DEMO_USERNAME = "admin";
    public static final String DEMO_PASSWORD = "admin@2026!";

    private static final Logger log = LoggerFactory.getLogger(TradeSecurityConfig.class);

    @Bean
    SecurityFilterChain tradeSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                // No browser session or cookie exists to forge a request against.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService tradeUsers(PasswordEncoder encoder,
            @Value("${trade.security.username:" + DEMO_USERNAME + "}") String username,
            @Value("${trade.security.password:" + DEMO_PASSWORD + "}") String password) {
        if (DEMO_PASSWORD.equals(password)) {
            log.warn("Using the built-in demo password. Set TRADE_SECURITY_PASSWORD before any real use.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
    }
}
