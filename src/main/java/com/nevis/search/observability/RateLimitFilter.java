package com.nevis.search.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single global token bucket across the whole service.
 *
 * <p>Global rather than per-caller: the API is unauthenticated, so there is no identity to meter
 * against. The limit bounds total load rather than metering individual callers.
 *
 * <p>Tokens refill continuously rather than resetting on a fixed window, so a caller who runs out
 * regains capacity gradually instead of everyone stampeding at the top of each minute.
 *
 * <p>The bucket is per instance, so the effective ceiling scales with the number of running
 * containers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final int capacity;
    private final double tokensPerNano;

    /** Scaled by 1000 so fractional refill survives in an atomic long. */
    private static final long SCALE = 1000L;

    private final AtomicLong tokens;
    private final AtomicLong lastRefillNanos = new AtomicLong(System.nanoTime());

    RateLimitFilter(@Value("${search.rate-limit.requests-per-minute:60}") int requestsPerMinute) {
        this.capacity = requestsPerMinute;
        this.tokensPerNano = requestsPerMinute / 60_000_000_000.0;
        this.tokens = new AtomicLong((long) requestsPerMinute * SCALE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean allowed = tryConsume();

        response.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, tokens.get() / SCALE)));

        if (!allowed) {
            int retryAfter = secondsUntilNextToken();
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            // Jakarta's servlet API has no SC_ constant for 429.
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"status":429,"error":"Too Many Requests",\
                    "message":"Rate limit of %d requests per minute exceeded. \
                    Tokens refill continuously; retry in %d second(s).",\
                    "retry_after_seconds":%d}"""
                    .formatted(capacity, retryAfter, retryAfter));

            MDC.put("rate_limited", "true");
            MDC.put("rate_limit_retry_after_s", String.valueOf(retryAfter));
            log.warn("Request rejected by rate limit");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Static assets are exempt. A page load pulls several of them, and spending the budget on
     * them would rate limit the UI out of existence before a user had searched once.
     */
    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/vendor/")
                || path.equals("/favicon.svg")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    /**
     * Refills for elapsed time, then takes a token if one is available.
     *
     * <p>A rejected request must not consume a token. Deducting unconditionally lets a burst of
     * rejections drive the balance further negative, so the bucket never recovers while traffic
     * continues and callers stay limited long after the window should have reopened.
     */
    private boolean tryConsume() {
        refill();
        while (true) {
            long current = tokens.get();
            if (current < SCALE) {
                return false;
            }
            if (tokens.compareAndSet(current, current - SCALE)) {
                return true;
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        long previous = lastRefillNanos.getAndSet(now);
        long elapsed = now - previous;
        if (elapsed <= 0) {
            return;
        }
        long earned = (long) (elapsed * tokensPerNano * SCALE);
        if (earned > 0) {
            tokens.updateAndGet(current -> Math.min((long) capacity * SCALE, current + earned));
        }
    }

    /** How long until the bucket holds a whole token again. */
    private int secondsUntilNextToken() {
        long needed = SCALE - Math.max(0, tokens.get());
        double seconds = needed / (double) SCALE * 60.0 / capacity;
        return Math.max(1, (int) Math.ceil(seconds));
    }
}
