package io.orkestra.spring;

import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.exception.ShutdownException;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class OrkestraLifecycleTest {
    @Test void springStartsAndStopsTheCoordinatorThroughItsLifecycle() throws Exception {
        Orchestrator coordinator;
        OrkestraLifecycle lifecycle;
        try (var context = new GenericApplicationContext()) {
            context.registerBean(Orchestrator.class, Orchestrator::new);
            context.registerBean(OrkestraLifecycle.class,
                    () -> new OrkestraLifecycle(context.getBean(Orchestrator.class), Duration.ofMillis(100), 0));
            context.refresh(); coordinator = context.getBean(Orchestrator.class); lifecycle = context.getBean(OrkestraLifecycle.class);
            assertTrue(lifecycle.isRunning());
            var definition = Compositions.<Void, Integer>create().perform((ctx, input) -> input + 1).build();
            assertEquals(2, coordinator.perform(definition, null, 1).output());
        }
        assertFalse(lifecycle.isRunning()); assertTrue(lifecycle.report().orElseThrow().complete());
        assertThrows(ShutdownException.class, () -> coordinator.perform(Compositions.<Void, Integer>create().build(), null, 1));
        assertThrows(IllegalStateException.class, lifecycle::start);
    }
}
