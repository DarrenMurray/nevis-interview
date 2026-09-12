package com.nevis.search.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Uniform error responses and one structured log line per failure.
 *
 * <p>Extends Spring's own handler rather than replacing it. A bare
 * {@code @ExceptionHandler(Exception.class)} swallows the framework's exceptions too, so a failed
 * {@code @Valid} check stops being a 400 and becomes a 500 - which is exactly what happened here
 * before this class was reworked.
 *
 * <p>Expected failures are logged at WARN without a stack trace; unexpected ones at ERROR with
 * the exception, because only those are worth alerting on. Both inherit the request context the
 * logging filter placed in the MDC, so an error is greppable by request id, client IP or search
 * term.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Every exception Spring itself maps passes through here, keeping its correct status. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        MDC.put("error_kind", "expected");
        MDC.put("http_status", String.valueOf(status.value()));
        log.warn("Request failed: {}", ex.getMessage());
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Anything unhandled. The response message is deliberately generic: an exception message can
     * carry SQL fragments or internal identifiers that do not belong in a public response. The
     * detail goes to the log, keyed by the request id the caller has in X-Request-Id.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        MDC.put("error_kind", "unexpected");
        log.error("Unhandled exception", e);

        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error. Quote the X-Request-Id header when reporting this.");
        body.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        String requestId = MDC.get("request_id");
        if (requestId != null) {
            body.setProperty("request_id", requestId);
        }
        return body;
    }
}
