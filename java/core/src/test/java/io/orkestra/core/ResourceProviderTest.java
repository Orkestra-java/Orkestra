package io.orkestra.core;

import io.orkestra.core.composition.Compositions;
import io.orkestra.core.spi.CacheStore;
import io.orkestra.core.spi.CircuitBreaker;
import io.orkestra.core.spi.LockProvider;
import io.orkestra.core.spi.ResourceProvider;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceProviderTest {
    @Test void customResourcesRemainOptional() throws Exception {
        var created = new AtomicInteger();
        ResourceProvider provider = new ResourceProvider() {
            @Override public LockProvider locks() { created.incrementAndGet(); return (key, budget) -> () -> {}; }
            @Override public CacheStore cache(int capacity) { throw new AssertionError("Unused cache must not be created"); }
            @Override public CircuitBreaker breaker(String name, int threshold, Duration interval) {
                throw new AssertionError("Unused breaker must not be created");
            }
        };
        var definition = Compositions.<Void, Integer>create().perform((context, input) -> input + 1).build();
        try (var coordinator = Orchestrator.builder().resources(provider).build()) {
            assertEquals(8, coordinator.perform(definition, null, 7).output());
            assertEquals(1, created.get());
        }
    }

    @Test void defaultsWorkWithoutProviderDiscovery() throws Exception {
        var definition = Compositions.<Void, Integer>create().perform((context, input) -> input + 1).build();
        var previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(new ClassLoader(null) {});
            try (var coordinator = new Orchestrator()) {
                assertEquals(8, coordinator.perform(definition, null, 7).output());
            }
        } finally { Thread.currentThread().setContextClassLoader(previous); }
    }
}
