package com.dws.trade.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A request body above the limit is refused before the application reads it. */
class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(100);

    /** A request that does not declare its length, as a chunked upload does. */
    private static MockHttpServletRequest undeclared(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trades") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void refusesARequestThatDeclaresATooLargeBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trades");
        request.setContent(new byte[500]);
        request.addHeader("Content-Length", "500");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger reached = new AtomicInteger();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                reached.incrementAndGet();
            }
        });

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(reached.get()).isZero();
    }

    @Test
    void letsASmallBodyThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trades");
        request.setContent(new byte[50]);
        request.addHeader("Content-Length", "50");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger bytesRead = new AtomicInteger();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException {
                bytesRead.set(req.getInputStream().readAllBytes().length);
            }
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bytesRead.get()).isEqualTo(50);
    }

    @Test
    void stopsReadingABodyThatTurnsOutLargerThanItsLimitWhenNoLengthWasDeclared() throws Exception {
        MockHttpServletRequest request = undeclared(500);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException {
                req.getInputStream().readAllBytes();
            }
        });

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void letsAnUndeclaredSmallBodyThrough() throws Exception {
        MockHttpServletRequest request = undeclared(60);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger bytesRead = new AtomicInteger();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException {
                bytesRead.set(req.getInputStream().readAllBytes().length);
            }
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(bytesRead.get()).isEqualTo(60);
    }
}
