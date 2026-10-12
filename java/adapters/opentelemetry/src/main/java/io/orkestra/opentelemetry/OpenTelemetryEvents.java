package io.orkestra.opentelemetry;

import io.orkestra.core.spi.ExecutionEvent;
import io.orkestra.core.spi.EventListener;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Converts library events into spans, without owning an SDK or global exporter. */
public final class OpenTelemetryEvents implements EventListener, AutoCloseable {
    private final Tracer tracer;
    private final Function<ExecutionEvent, Context> rootContext;
    private final int capacity;
    private final Map<String, Span> spans = new HashMap<>();
    private boolean closed;
    public OpenTelemetryEvents(Tracer tracer) { this(tracer, event -> Context.root(), 4096); }
    public OpenTelemetryEvents(Tracer tracer, Function<ExecutionEvent, Context> rootContext, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Span capacity must be positive");
        this.tracer = Objects.requireNonNull(tracer); this.rootContext = Objects.requireNonNull(rootContext); this.capacity = capacity;
    }
    @Override public synchronized void onEvent(ExecutionEvent event) {
        if (closed) return;
        if (event.type().equals("STARTED") || event.type().equals("COMPENSATION_STARTED")) {
            if (spans.containsKey(event.spanId())) return;
            if (spans.size() >= capacity) throw new IllegalStateException("Active telemetry span limit reached");
            Span parent = spans.get(event.parentSpanId());
            Context context = parent == null ? rootContext.apply(event) : Context.root().with(parent);
            var builder = tracer.spanBuilder(event.name()).setParent(Objects.requireNonNull(context))
                    .setStartTimestamp(event.at())
                    .setAttribute("orkestra.execution.id", event.executionId())
                    .setAttribute("orkestra.span.id", event.spanId())
                    .setAttribute("orkestra.kind", event.kind());
            if (event.correlationId() != null) builder.setAttribute("orkestra.correlation.id", event.correlationId());
            event.attributes().forEach((key, value) -> builder.setAttribute("orkestra.attribute." + key, value));
            spans.put(event.spanId(), builder.startSpan());
            return;
        }
        Span span = spans.get(event.spanId());
        if (span == null) return;
        if (event.failure() != null) { span.recordException(event.failure()); span.setStatus(StatusCode.ERROR); }
        span.addEvent(event.type(), Attributes.empty(), event.at());
        if (event.type().equals("SUCCEEDED") || event.type().equals("FAILED")
                || event.type().equals("COMPENSATION_SUCCEEDED") || event.type().equals("COMPENSATION_FAILED")) {
            spans.remove(event.spanId()); span.end(event.at());
        }
    }
    public synchronized int activeSpans() { return spans.size(); }
    @Override public synchronized void close() {
        closed = true;
        for (var span : spans.values()) { span.setStatus(StatusCode.ERROR, "Exporter closed with unfinished span"); span.end(); }
        spans.clear();
    }
}
