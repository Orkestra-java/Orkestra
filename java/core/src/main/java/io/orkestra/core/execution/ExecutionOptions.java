package io.orkestra.core.execution;

import java.util.Map;

/** Explicit observability attributes. Application context is never exported automatically. */
public record ExecutionOptions(String correlationId, Map<String, String> attributes) {
    public ExecutionOptions { attributes = Map.copyOf(attributes); }
    public static ExecutionOptions defaults() { return new ExecutionOptions(null, Map.of()); }
}
