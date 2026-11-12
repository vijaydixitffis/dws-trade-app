package com.dws.trade.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limits how many requests each caller may make in a second: a fixed one-second window per
 * signed-in user, or per address when there is no login. Held in memory, so it is per instance;
 * a shared limit across instances belongs in a gateway.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final int PURGE_ABOVE = 10_000;

    private record Window(long startNanos, int count) {
    }

    private final int limit;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(int limitPerSecond, LongSupplier nanoTime) {
        this.limit = limitPerSecond;
        this.nanoTime = nanoTime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (limit <= 0 || allow(callerOf(request))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", "1");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429}");
    }

    private boolean allow(String caller) {
        long now = nanoTime.getAsLong();
        AtomicBoolean allowed = new AtomicBoolean();
        windows.compute(caller, (key, window) -> {
            if (window == null || now - window.startNanos() >= WINDOW_NANOS) {
                allowed.set(true);
                return new Window(now, 1);
            }
            if (window.count() < limit) {
                allowed.set(true);
                return new Window(window.startNanos(), window.count() + 1);
            }
            return window;
        });
        if (windows.size() > PURGE_ABOVE) {
            windows.values().removeIf(w -> now - w.startNanos() >= 2 * WINDOW_NANOS);
        }
        return allowed.get();
    }

    private static String callerOf(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "user:" + authentication.getName();
        }
        return "address:" + request.getRemoteAddr();
    }
}
