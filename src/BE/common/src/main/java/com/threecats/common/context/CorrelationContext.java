package com.threecats.common.context;

import java.util.UUID;

public class CorrelationContext {
    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            CORRELATION_ID.set(UUID.randomUUID().toString());
        } else {
            CORRELATION_ID.set(correlationId);
        }
    }

    public static String get() {
        String id = CORRELATION_ID.get();
        if (id == null) {
            id = UUID.randomUUID().toString();
            CORRELATION_ID.set(id);
        }
        return id;
    }

    public static void clear() {
        CORRELATION_ID.remove();
    }
}
