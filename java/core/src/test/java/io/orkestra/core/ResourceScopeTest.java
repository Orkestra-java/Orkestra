package io.orkestra.core;

import io.orkestra.core.Failures;
import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.composition.TaskRef;
import io.orkestra.core.execution.FailureKind;
import io.orkestra.core.local.lock.LocalLocks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ResourceScopeTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void rejectsAncestorResourceBeforeWaiting(boolean exclusive, boolean cached) throws Exception {
        var entered = new AtomicInteger();
        var keys = new AtomicInteger();
        var locks = new LocalLocks();
        var leaf = TaskRef.<String, Integer, Integer>of((context, input) -> {
            entered.incrementAndGet(); return input;
        });
        leaf = exclusive ? leaf.exclusively(context -> { keys.incrementAndGet(); return context; })
                : leaf.isolate("shared").concurrently(1);
        if (cached) leaf = leaf.cache(cache -> cache.key(context -> context).expiresAfter(Duration.ofMinutes(1)));
        var inner = Compositions.<String, Integer>create().perform(leaf).build();
        var parent = Compositions.<String, Integer>create().perform(inner);
        var definition = (exclusive ? parent.exclusively(context -> { keys.incrementAndGet(); return context; })
                : parent.isolate("shared").concurrently(1)).build().within(Duration.ofSeconds(2));
        try (var coordinator = Orchestrator.builder().locks(locks).build()) {
            var error = assertThrows(IllegalArgumentException.class, () -> coordinator.perform(definition, "key", 1));
            assertTrue(error.getMessage().contains("ancestor"));
            assertEquals(FailureKind.TASK_FAILED, Failures.details(error).orElseThrow().kind());
            assertEquals(0, entered.get());
            assertEquals(0, locks.size());
            if (exclusive) assertEquals(2, keys.get(), "one key evaluation per scope");
            assertEquals(7, coordinator.perform(inner, "key", 7).output(), "failed parent releases its resource");
        }
    }

    @Test void rejectsResourceReacquisitionAcrossParallelWorkers() {
        var ref = TaskRef.<Void, Integer, Integer>of((context, input) -> input).isolate("shared").concurrently(1);
        var inner = Compositions.<Void, Integer>create().parallel(group -> group.perform(ref))
                .join((context, results) -> results.get(ref)).build();
        var definition = Compositions.<Void, Integer>create().perform(inner).isolate("shared").concurrently(1).build();
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            try (var coordinator = new Orchestrator()) {
                assertThrows(IllegalArgumentException.class, () -> coordinator.perform(definition, null, 1));
            }
        });
    }

    @Test void differentNestedKeysAndSequentialReuseRemainValid() throws Exception {
        var keys = new AtomicInteger();
        var leaf = TaskRef.<String, Integer, Integer>of((context, input) -> input + 1)
                .exclusively(context -> { keys.incrementAndGet(); return "child"; }).isolate("child").concurrently(1);
        var inner = Compositions.<String, Integer>create().perform(leaf).perform(leaf).build();
        var outer = Compositions.<String, Integer>create().perform(inner).exclusively(context -> "parent")
                .isolate("parent").concurrently(1).build();
        try (var coordinator = new Orchestrator()) {
            assertEquals(3, coordinator.perform(outer, "context", 1).output());
            assertEquals(4, coordinator.perform(outer, "context", 2).output());
            assertEquals(4, keys.get());
        }
    }

    @Test void cacheHitDoesNotReacquireResourcesHeldByParent() throws Exception {
        var keys = new AtomicInteger();
        var leaf = TaskRef.<String, Integer, Integer>of((context, input) -> input)
                .exclusively(context -> { keys.incrementAndGet(); return context; })
                .cache(cache -> cache.key(context -> context).expiresAfter(Duration.ofMinutes(1)));
        var inner = Compositions.<String, Integer>create().perform(leaf).build();
        var outer = Compositions.<String, Integer>create().perform(inner).exclusively(context -> context).build();
        try (var coordinator = new Orchestrator()) {
            assertEquals(1, coordinator.perform(inner, "key", 1).output());
            assertEquals(1, coordinator.perform(outer, "key", 2).output());
            assertEquals(1, keys.get(), "cache hit must not evaluate the exclusive key");
        }
    }
}
