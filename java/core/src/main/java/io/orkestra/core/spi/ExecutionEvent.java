package io.orkestra.core.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Structured event. Attributes are explicitly selected; no business payload is captured. */
public record ExecutionEvent(String executionId, String correlationId, String spanId,
        String parentSpanId, String name, String kind, String type, Instant at,
        Duration duration, Throwable failure, Map<String, String> attributes) {
    public ExecutionEvent { attributes = Map.copyOf(attributes); }
}
