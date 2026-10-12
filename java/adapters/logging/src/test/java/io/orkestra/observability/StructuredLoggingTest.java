package io.orkestra.observability;

import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.execution.ExecutionOptions;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.logging.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredLoggingTest {
    @Test void recordsStructuredEventsAndEscapesAttributesWithoutExportingContext() throws Exception {
        var records = new ArrayList<String>();
        var logger = Logger.getLogger(UUID.randomUUID().toString()); logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        var composition = Compositions.<String, Integer>create().perform((context, input) -> input + 1).build();
        try (var coordinator = Orchestrator.builder().events(new StructuredLogging(logger)).build()) {
            var execution = coordinator.perform(composition, "private-business-context", 1,
                    new ExecutionOptions("request", Map.of("quoted", "a\n\"b\u0001")));
            assertTrue(records.stream().allMatch(json -> json.contains(execution.id())));
            assertTrue(records.stream().allMatch(json -> json.startsWith("{") && json.endsWith("}")));
            assertTrue(records.stream().allMatch(json -> json.contains("a\\n\\\"b\\u0001")));
            assertTrue(records.stream().noneMatch(json -> json.contains("private-business-context")));
        }
    }
}
