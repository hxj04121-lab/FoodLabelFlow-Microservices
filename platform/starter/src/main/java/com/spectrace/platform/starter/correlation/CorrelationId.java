package com.spectrace.platform.starter.correlation;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The correlation ID that ties a request, its downstream calls, its log lines and the events it
 * publishes together. It travels in the {@value #HEADER} header and in the event envelope's
 * {@code correlationId}, and is held in the logging MDC under {@value #MDC_KEY}.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";
    public static final String REQUEST_ATTRIBUTE = CorrelationId.class.getName();

    /** Same bound as the contract's Id type, restricted to characters that are safe in logs and headers. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private CorrelationId() {
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** The correlation ID of the work running on this thread, if any. */
    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }

    /** The correlation ID bound to this request, also during the error dispatch that follows it. */
    public static String of(HttpServletRequest request) {
        if (request != null && request.getAttribute(REQUEST_ATTRIBUTE) instanceof String value) {
            return value;
        }
        return MDC.get(MDC_KEY);
    }

    /**
     * Binds a correlation ID to the current thread until the returned scope is closed, restoring
     * whatever was bound before. Message consumers use it with the envelope's {@code correlationId}.
     */
    public static Scope bind(String correlationId) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, isValid(correlationId) ? correlationId : newId());
        return () -> {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        };
    }

    /** A binding that is undone on close. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
