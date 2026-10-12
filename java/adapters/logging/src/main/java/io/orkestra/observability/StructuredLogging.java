package io.orkestra.observability;

import io.orkestra.core.spi.ExecutionEvent;
import io.orkestra.core.spi.EventListener;

import java.util.Objects;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** JSON logging adapter. The application owns JUL handlers and destinations. */
public final class StructuredLogging implements EventListener {
    private final Logger logger;
    public StructuredLogging(Logger logger) { this.logger = Objects.requireNonNull(logger); }
    @Override public void onEvent(ExecutionEvent event) {
        var json = new StringBuilder("{");
        field(json, "executionId", event.executionId());
        field(json, "correlationId", event.correlationId());
        field(json, "spanId", event.spanId());
        field(json, "parentSpanId", event.parentSpanId());
        field(json, "name", event.name());
        field(json, "kind", event.kind());
        field(json, "type", event.type());
        field(json, "at", event.at().toString());
        field(json, "duration", event.duration().toString());
        field(json, "errorType", event.failure() == null ? null : event.failure().getClass().getName());
        json.append(",\"attributes\":{");
        boolean first = true;
        for (var attribute : new TreeMap<>(event.attributes()).entrySet()) {
            if (!first) json.append(','); first = false;
            json.append(quote(attribute.getKey())).append(':').append(quote(attribute.getValue()));
        }
        json.append("}}");
        logger.log(event.failure() == null ? Level.INFO : Level.WARNING, json.toString());
    }
    private static void field(StringBuilder json, String key, String value) {
        if (json.length() > 1) json.append(',');
        json.append(quote(key)).append(':').append(quote(value));
    }
    private static String quote(String value) {
        if (value == null) return "null";
        var escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> { if (character < 32) escaped.append(String.format("\\u%04x", (int) character)); else escaped.append(character); }
            }
        }
        return escaped.append('"').toString();
    }
}
