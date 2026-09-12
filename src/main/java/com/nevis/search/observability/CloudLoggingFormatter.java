package com.nevis.search.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import java.util.Map;

/**
 * One JSON object per log line, in the shape Cloud Logging understands.
 *
 * <p>Spring Boot ships ECS, GELF and Logstash formats, none of which Cloud Logging reads
 * natively: it keys severity off a top-level {@code severity} field, so with any of those every
 * line arrives as DEFAULT and errors become indistinguishable from info. This emits
 * {@code severity} and {@code message}, which is all Cloud Run needs to parse stdout into
 * structured entries.
 *
 * <p>MDC entries are promoted to top-level fields, so anything put there by
 * {@link RequestLoggingFilter} (request id, client ip, user agent, search term) becomes queryable
 * in Logs Explorer rather than buried in a message string.
 *
 * <p>Selected with {@code logging.structured.format.console}; see application.yaml.
 */
public class CloudLoggingFormatter implements StructuredLogFormatter<ILoggingEvent> {

    @Override
    public String format(ILoggingEvent event) {
        StringBuilder json = new StringBuilder(256).append('{');

        // Cloud Logging maps these two onto the entry itself rather than the payload.
        field(json, "severity", severityOf(event), true);
        field(json, "message", event.getFormattedMessage(), false);
        field(json, "logger", event.getLoggerName(), false);
        field(json, "thread", event.getThreadName(), false);
        field(json, "timestamp", java.time.Instant.ofEpochMilli(event.getTimeStamp()).toString(), false);

        for (Map.Entry<String, String> entry : event.getMDCPropertyMap().entrySet()) {
            // Reserved above; an MDC key of the same name must not produce duplicate JSON keys.
            if (!isReserved(entry.getKey())) {
                field(json, entry.getKey(), entry.getValue(), false);
            }
        }

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            field(json, "exception", throwable.getClassName(), false);
            field(json, "exception_message", throwable.getMessage(), false);
            // Cloud Error Reporting picks up a stack trace on this field.
            field(json, "stack_trace", ThrowableProxyUtil.asString(throwable), false);
        }

        return json.append("}\n").toString();
    }

    /** Logback has no FATAL; the rest map one to one onto Cloud Logging severities. */
    private static String severityOf(ILoggingEvent event) {
        return switch (event.getLevel().toInt()) {
            case ch.qos.logback.classic.Level.ERROR_INT -> "ERROR";
            case ch.qos.logback.classic.Level.WARN_INT -> "WARNING";
            case ch.qos.logback.classic.Level.DEBUG_INT -> "DEBUG";
            case ch.qos.logback.classic.Level.TRACE_INT -> "DEBUG";
            default -> "INFO";
        };
    }

    private static boolean isReserved(String key) {
        return switch (key) {
            case "severity", "message", "logger", "thread", "timestamp",
                 "exception", "exception_message", "stack_trace" -> true;
            default -> false;
        };
    }

    private static void field(StringBuilder json, String key, String value, boolean first) {
        if (value == null) {
            return;
        }
        if (!first) {
            json.append(',');
        }
        escape(json.append('"'), key).append("\":\"");
        escape(json, value).append('"');
    }

    /** Minimal JSON string escaping. A raw quote or newline would corrupt the whole entry. */
    private static StringBuilder escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out;
    }
}
