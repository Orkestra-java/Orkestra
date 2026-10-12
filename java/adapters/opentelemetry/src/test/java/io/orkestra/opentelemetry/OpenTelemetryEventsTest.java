package io.orkestra.opentelemetry;

import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.task.Task;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetryEventsTest {
    @Test void exportsRealSdkSpansWithHierarchyAndFailureStatus() throws Exception {
        var exported = new ConcurrentLinkedQueue<SpanData>();
        var exporter = new SpanExporter() {
            @Override public CompletableResultCode export(Collection<SpanData> spans) { exported.addAll(spans); return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            @Override public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
             var events = new OpenTelemetryEvents(provider.get("orkestra"));
             var coordinator = Orchestrator.builder().events(events).build()) {
            var composition = Compositions.<Void, Integer>create().named("order")
                    .perform((context, input) -> input + 1).named("validate").build();
            coordinator.perform(composition, null, 1);
            var root = exported.stream().filter(span -> span.getName().equals("order") && span.getParentSpanId().equals("0000000000000000")).findFirst().orElseThrow();
            var child = exported.stream().filter(span -> span.getName().equals("validate")).findFirst().orElseThrow();
            assertEquals(root.getTraceId(), child.getTraceId()); assertNotEquals("0000000000000000", child.getParentSpanId());
            assertEquals(0, events.activeSpans());
            Task<Void, Integer, Integer> failed = (context, input) -> { throw new IllegalArgumentException("rejected"); };
            var failure = Compositions.<Void, Integer>create().perform(failed).named("failed-task").build();
            assertThrows(IllegalArgumentException.class, () -> coordinator.perform(failure, null, 1));
            assertEquals(StatusCode.ERROR, exported.stream().filter(span -> span.getName().equals("failed-task")).findFirst().orElseThrow().getStatus().getStatusCode());
            assertEquals(0, events.activeSpans());
        }
    }
}
