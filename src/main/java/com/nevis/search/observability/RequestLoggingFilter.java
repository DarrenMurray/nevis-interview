package com.nevis.search.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * One structured line per request, with the context needed to answer "who searched for what".
 *
 * <p>Cloud Run already records method, path and status, but not the search term, and its request
 * log cannot be correlated with anything the application logs. Putting these fields in the MDC
 * means every log line emitted while handling a request carries them, including errors.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** Long queries are truncated: a log line is not a place to store arbitrary user input. */
    private static final int MAX_QUERY_CHARS = 200;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        String requestId = requestId(request);

        MDC.put("request_id", requestId);
        MDC.put("http_method", request.getMethod());
        MDC.put("http_path", request.getRequestURI());
        MDC.put("client_ip", clientIp(request));
        putIfPresent("user_agent", request.getHeader("User-Agent"));
        putIfPresent("referer", request.getHeader("Referer"));
        putIfPresent("search_term", truncate(request.getParameter("q")));

        // Echoed so a caller can quote it when reporting a problem.
        response.setHeader("X-Request-Id", requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            MDC.put("http_status", String.valueOf(response.getStatus()));
            MDC.put("duration_ms", String.valueOf(millis));

            if (response.getStatus() >= 500) {
                log.error("Request failed");
            } else if (response.getStatus() >= 400) {
                log.warn("Request rejected");
            } else {
                log.info("Request handled");
            }
            // Cleared rather than removed key by key: the thread is pooled and reused.
            MDC.clear();
        }
    }

    /**
     * Static assets would otherwise double the log volume without adding information.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/vendor/") || path.equals("/favicon.svg");
    }

    /** Reuses an inbound id when there is one, so a trace survives across services. */
    private static String requestId(HttpServletRequest request) {
        String inbound = request.getHeader("X-Request-Id");
        return inbound != null && !inbound.isBlank()
                ? inbound.substring(0, Math.min(inbound.length(), 64))
                : UUID.randomUUID().toString();
    }

    /**
     * Behind Cloud Run's proxy the socket address is the load balancer, so the caller's address
     * is the first entry of X-Forwarded-For. Later entries are the proxies it passed through.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= MAX_QUERY_CHARS ? value : value.substring(0, MAX_QUERY_CHARS) + "...";
    }
}
