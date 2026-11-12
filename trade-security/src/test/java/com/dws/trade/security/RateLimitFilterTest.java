package com.dws.trade.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Each caller gets a fixed number of requests per second; the rest are told to wait. */
class RateLimitFilterTest {

    private final AtomicLong nanos = new AtomicLong();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(String user) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(user, "x", "trades:read"));
    }

    private int call(RateLimitFilter filter) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/trades"), response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void allowsTheLimitWithinASecondAndRefusesTheRestWithARetryHint() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3, nanos::get);
        signedInAs("alice");

        assertThat(call(filter)).isEqualTo(200);
        assertThat(call(filter)).isEqualTo(200);
        assertThat(call(filter)).isEqualTo(200);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/trades"), refused, new MockFilterChain());

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void startsAFreshWindowEachSecond() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2, nanos::get);
        signedInAs("alice");
        call(filter);
        call(filter);
        assertThat(call(filter)).isEqualTo(429);

        nanos.addAndGet(1_100_000_000L);

        assertThat(call(filter)).isEqualTo(200);
    }

    @Test
    void countsEachCallerSeparately() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, nanos::get);
        signedInAs("alice");
        assertThat(call(filter)).isEqualTo(200);
        assertThat(call(filter)).isEqualTo(429);

        signedInAs("bob");

        assertThat(call(filter)).isEqualTo(200);
    }

    @Test
    void callersWithoutALoginAreCountedByAddress() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, nanos::get);
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/v1/trades");
        first.setRemoteAddr("10.0.0.1");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/v1/trades");
        second.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse a = new MockHttpServletResponse();
        MockHttpServletResponse b = new MockHttpServletResponse();
        MockHttpServletResponse c = new MockHttpServletResponse();

        filter.doFilter(first, a, new MockFilterChain());
        filter.doFilter(first, b, new MockFilterChain());
        filter.doFilter(second, c, new MockFilterChain());

        assertThat(a.getStatus()).isEqualTo(200);
        assertThat(b.getStatus()).isEqualTo(429);
        assertThat(c.getStatus()).isEqualTo(200);
    }

    @Test
    void aLimitOfZeroSwitchesItOff() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(0, nanos::get);
        signedInAs("alice");

        for (int i = 0; i < 50; i++) {
            assertThat(call(filter)).isEqualTo(200);
        }
    }
}
