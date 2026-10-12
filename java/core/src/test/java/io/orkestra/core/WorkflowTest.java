package io.orkestra.core;

import io.orkestra.core.Failures;
import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Composition;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.composition.TaskRef;
import io.orkestra.core.exception.CircuitOpenException;
import io.orkestra.core.exception.RequirementException;
import io.orkestra.core.exception.ShutdownException;
import io.orkestra.core.execution.Execution;
import io.orkestra.core.execution.ExecutionOptions;
import io.orkestra.core.execution.FailureKind;
import io.orkestra.core.spi.ExecutionEvent;
import io.orkestra.core.task.Task;
import io.orkestra.core.local.cache.MemoryCache;
import io.orkestra.core.local.events.AsyncEventListener;
import io.orkestra.core.local.history.MemoryHistory;
import io.orkestra.core.local.lock.LocalLocks;
import io.orkestra.core.local.resilience.LocalCircuitBreaker;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowTest {
    static void ready(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Work did not reach its synchronization point");
    }
    static void until(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2);
        assertTrue(condition.getAsBoolean());
    }
    static void stubborn(CountDownLatch release) {
        while (release.getCount() > 0) {
            try { release.await(); } catch (InterruptedException ignored) { }
        }
    }
    static <C, I, O> Callable<Execution<O>> call(Orchestrator coordinator, Composition<C, I, O> definition, C context, I input) {
        return () -> coordinator.perform(definition, context, input);
    }

    @Test void parallelOverlapsAndResultsRemainTypedByIdentity() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var first = TaskRef.<Void, Integer, String>of((context, input) -> { entered.countDown(); ready(release); return "value:" + input; }).named("same");
        var second = TaskRef.<Void, Integer, Integer>of((context, input) -> { entered.countDown(); ready(release); return input * 2; }).named("same");
        var composition = Compositions.<Void, Integer>create()
                .parallel(group -> group.concurrently(2).perform(first).perform(second))
                .join((context, results) -> results.get(first) + "/" + results.get(second)).build();
        try (var coordinator = new Orchestrator(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(call(coordinator, composition, null, 3));
            try { ready(entered); } finally { release.countDown(); }
            assertEquals("value:3/6", future.get(3, TimeUnit.SECONDS).output());
        }
    }

    @Test void duplicateAndForeignReferencesAreRejected() throws Exception {
        var reference = TaskRef.<Void, Integer, Integer>of((context, input) -> input);
        assertThrows(IllegalArgumentException.class, () -> Compositions.<Void, Integer>create()
                .parallel(group -> group.perform(reference).perform(reference)));
        var foreign = TaskRef.<Void, Integer, Integer>of((context, input) -> input);
        var composition = Compositions.<Void, Integer>create().parallel(group -> group.perform(reference))
                .join((context, results) -> results.get(foreign)).build();
        try (var coordinator = new Orchestrator()) {
            assertThrows(IllegalArgumentException.class, () -> coordinator.perform(composition, null, 1));
        }
    }

    @Test void failedBranchCancelsSiblingAndNeverInvokesJoin() throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var joined = new AtomicInteger();
        Task<Void, Integer, Integer> slow = (context, input) -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); return input; }
            catch (InterruptedException error) { cancelled.countDown(); throw error; }
        };
        Task<Void, Integer, Integer> failed = (context, input) -> { ready(entered); throw new IOException("branch failed"); };
        var composition = Compositions.<Void, Integer>create().parallel(group -> group.perform(slow).perform(failed))
                .join((context, results) -> joined.incrementAndGet()).build();
        try (var coordinator = new Orchestrator()) {
            assertThrows(IOException.class, () -> coordinator.perform(composition, null, 1));
            ready(cancelled); assertEquals(0, joined.get());
        }
    }

    @Test void groupConcurrencyLimitIsEnforced() throws Exception {
        var active = new AtomicInteger(); var maximum = new AtomicInteger();
        Task<Void, Integer, Integer> task = (context, input) -> {
            int count = active.incrementAndGet(); maximum.accumulateAndGet(count, Math::max);
            try { Thread.sleep(15); return input; } finally { active.decrementAndGet(); }
        };
        var composition = Compositions.<Void, Integer>create().parallel(group -> {
            group.concurrently(2); for (int i = 0; i < 8; i++) group.perform(task);
        }).join((context, results) -> 1).build();
        try (var coordinator = new Orchestrator()) { coordinator.perform(composition, null, 1); }
        assertEquals(2, maximum.get()); assertEquals(0, active.get());
    }

    @Test void forEachKeepsInputOrderAndSharesContext() throws Exception {
        var composition = Compositions.<String, List<Integer>>create()
                .forEach(items -> items, item -> item.concurrently(3)
                        .perform((context, input) -> { Thread.sleep((4 - input) * 5L); return context + input; }))
                .build();
        try (var coordinator = new Orchestrator()) {
            assertEquals(List.of("item1", "item2", "item3"), coordinator.perform(composition, "item", List.of(1, 2, 3)).output());
            assertEquals(List.of(), coordinator.perform(composition, "item", List.of()).output());
        }
    }

    @Test void requirementsPreserveInputOrStopBeforeSideEffects() throws Exception {
        var calls = new AtomicInteger();
        var composition = Compositions.<String, Integer>create().require((context, input) -> input > 0)
                .perform((context, input) -> { calls.incrementAndGet(); return input; }).build();
        try (var coordinator = new Orchestrator()) {
            assertEquals(4, coordinator.perform(composition, "context", 4).output());
            assertThrows(RequirementException.class, () -> coordinator.perform(composition, "context", -1));
        }
        assertEquals(1, calls.get());
    }

    @Test void situationsSelectBooleanAndValuePaths() throws Exception {
        Task<Void, Integer, String> positive = (context, input) -> "positive";
        Task<Void, Integer, String> fallback = (context, input) -> "other";
        var predicate = Compositions.<Void, Integer>create().<String>situation(choice ->
                choice.when((context, input) -> input > 0).then(positive).otherwise(fallback)).build();
        var selectors = new AtomicInteger();
        Task<Void, Integer, Integer> selector = (context, input) -> { selectors.incrementAndGet(); return input; };
        var value = Compositions.<Void, Integer>create().<String>situation(choice ->
                choice.basedOn(selector).is(1).then(positive).otherwise(fallback)).build();
        try (var coordinator = new Orchestrator()) {
            assertEquals("positive", coordinator.perform(predicate, null, 2).output());
            assertEquals("other", coordinator.perform(predicate, null, -2).output());
            assertEquals("positive", coordinator.perform(value, null, 1).output());
            assertEquals("other", coordinator.perform(value, null, 9).output());
        }
        assertEquals(2, selectors.get());
        assertThrows(NullPointerException.class, () -> Compositions.<Void, Integer>create()
                .<String>situation(choice -> choice.when((context, input) -> true).then(positive)));
    }

    @Test void retriesAreExplicitAndCountTotalAttempts() throws Exception {
        var attempts = new AtomicInteger();
        var composition = Compositions.<Void, Integer>create().perform((context, input) -> {
            if (attempts.incrementAndGet() < 3) throw new IOException("transient"); return input + 1;
        }).retry(retry -> retry.upTo(3).retryIf(IOException.class::isInstance)).build();
        try (var coordinator = new Orchestrator()) { assertEquals(2, coordinator.perform(composition, null, 1).output()); }
        assertEquals(3, attempts.get());
        attempts.set(0);
        var noRule = Compositions.<Void, Integer>create().perform((context, input) -> {
            attempts.incrementAndGet(); throw new IllegalArgumentException("business rejection");
        }).retry(retry -> retry.upTo(3)).build();
        try (var coordinator = new Orchestrator()) { assertThrows(IllegalArgumentException.class, () -> coordinator.perform(noRule, null, 1)); }
        assertEquals(1, attempts.get());
    }

    @Test void breakerOpensAndAllowsOneHalfOpenProbe() throws Exception {
        var breaker = new LocalCircuitBreaker("service", 1, Duration.ofMillis(30));
        breaker.acquire().failure();
        assertThrows(CircuitOpenException.class, breaker::acquire);
        Thread.sleep(40);
        var probe = breaker.acquire();
        assertThrows(CircuitOpenException.class, breaker::acquire);
        probe.success(); breaker.acquire().success();
    }

    @Test void sharedNamedBreakerIsAppliedPerAttempt() {
        var calls = new AtomicInteger();
        var composition = Compositions.<Void, Integer>create().perform((context, input) -> {
            calls.incrementAndGet(); throw new IOException("failed");
        }).guard("service").build();
        try (var coordinator = Orchestrator.builder().breakerDefaults(1, Duration.ofSeconds(10)).build()) {
            assertThrows(IOException.class, () -> coordinator.perform(composition, null, 1));
            assertThrows(CircuitOpenException.class, () -> coordinator.perform(composition, null, 2));
        }
        assertEquals(1, calls.get());
    }

    @Test void taskHandlerWinsAndRunsAfterCompensation() {
        var events = new ArrayList<String>();
        Task<List<String>, Integer, Integer> fail = (context, input) -> { throw new IOException("failed"); };
        var composition = Compositions.<List<String>, Integer>create().withCompensation(group -> group
                .perform((context, input) -> input + 1).compensateWith((context, input, output) -> context.add("undo"))
                .perform(fail).onFailure((context, cause) -> { context.add("handler"); return new IllegalStateException("translated", cause); }))
                .build().onFailure((context, cause) -> new IllegalArgumentException("outer", cause));
        try (var coordinator = new Orchestrator()) {
            assertThrows(IllegalStateException.class, () -> coordinator.perform(composition, events, 1));
        }
        assertEquals(List.of("undo", "handler"), events);
    }

    @Test void compensationsRunInReverseAndReceiveOriginalInputsAndOutputs() {
        var trace = new ArrayList<String>();
        Task<List<String>, Integer, Integer> fail = (context, input) -> { throw new IOException("failed"); };
        var composition = Compositions.<List<String>, Integer>create().withCompensation(group -> group
                .perform((context, input) -> input + 1).compensateWith((context, input, output) -> context.add("first:" + input + ":" + output))
                .perform((context, input) -> input * 2).compensateWith((context, input, output) -> context.add("second:" + input + ":" + output))
                .perform(fail)).build();
        try (var coordinator = new Orchestrator()) { assertThrows(IOException.class, () -> coordinator.perform(composition, trace, 2)); }
        assertEquals(List.of("second:3:6", "first:2:3"), trace);
    }

    @Test void explicitSuccessfulGroupCompensationReplacesInternalActions() {
        var trace = new ArrayList<String>();
        Task<List<String>, Integer, Integer> fail = (context, input) -> { throw new IOException("failed"); };
        var composition = Compositions.<List<String>, Integer>create()
                .withCompensation(group -> group.perform((context, input) -> input + 1)
                        .compensateWith((context, input, output) -> context.add("internal")))
                .compensateWith((context, input, output) -> context.add("group:" + input + ":" + output))
                .perform(fail).build();
        try (var coordinator = new Orchestrator()) { assertThrows(IOException.class, () -> coordinator.perform(composition, trace, 1)); }
        assertEquals(List.of("group:1:2"), trace);
    }

    @Test void compensationFailuresDoNotPreventRemainingActions() {
        var trace = new ArrayList<String>();
        Task<List<String>, Integer, Integer> fail = (context, input) -> { throw new IOException("failed"); };
        var composition = Compositions.<List<String>, Integer>create().withCompensation(group -> group
                .perform((context, input) -> input).compensateWith((context, input, output) -> context.add("first"))
                .perform((context, input) -> input).compensateWith((context, input, output) -> { throw new IOException("undo failed"); })
                .perform(fail)).build();
        try (var coordinator = new Orchestrator()) {
            var thrown = assertThrows(IOException.class, () -> coordinator.perform(composition, trace, 1));
            var details = Failures.details(thrown).orElseThrow();
            assertTrue(details.compensationFailed()); assertEquals(1, details.compensationErrors().size());
        }
        assertEquals(List.of("first"), trace);
    }

    @Test void timeoutReportsNonCooperativeWorkAndSkipsUnsafeCompensation() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1); var undone = new AtomicInteger();
        var composition = Compositions.<Void, Integer>create().withCompensation(group -> group
                .perform((context, input) -> input).compensateWith((context, input, output) -> undone.incrementAndGet())
                .perform((context, input) -> { entered.countDown(); stubborn(release); return input; }))
                .build().within(Duration.ofMillis(150));
        try (var coordinator = Orchestrator.builder().cancellationBudget(Duration.ofMillis(20)).build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(call(coordinator, composition, null, 1));
            ready(entered);
            try {
                var wrapped = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
                var details = Failures.details(wrapped).orElseThrow();
                assertEquals(FailureKind.TIMEOUT, details.kind()); assertTrue(details.workPending()); assertEquals(0, undone.get());
            } finally { release.countDown(); }
        }
    }

    @Test void deadlineIncludesCapacityWaitAndDoesNotReleaseAnActiveOwnersPermit() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var reference = TaskRef.<Void, Integer, Integer>of((context, input) -> {
            calls.incrementAndGet(); entered.countDown(); stubborn(release); return input;
        }).concurrently(1);
        var composition = Compositions.<Void, Integer>create().perform(reference).build();
        try (var coordinator = Orchestrator.builder().cancellationBudget(Duration.ofMillis(20)).build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(call(coordinator, composition, null, 1)); ready(entered);
            try {
                var timed = composition.within(Duration.ofMillis(50));
                var failure = assertThrows(TimeoutException.class, () -> coordinator.perform(timed, null, 2));
                assertEquals(FailureKind.TIMEOUT, Failures.details(failure).orElseThrow().kind()); assertEquals(1, calls.get());
            } finally { release.countDown(); }
            assertEquals(1, owner.get(2, TimeUnit.SECONDS).output());
            assertEquals(3, coordinator.perform(composition, null, 3).output());
        }
    }

    @Test void exclusiveLocksSerializeAndAreRemovedAfterFailure() throws Exception {
        var locks = new LocalLocks(); var active = new AtomicInteger(); var max = new AtomicInteger();
        var composition = Compositions.<String, Integer>create().perform((context, input) -> {
            max.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { Thread.sleep(10); if (input < 0) throw new IOException("failed"); return input; }
            finally { active.decrementAndGet(); }
        }).exclusively(context -> context).build();
        try (var coordinator = Orchestrator.builder().locks(locks).build(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(call(coordinator, composition, "same", 1));
            var second = executor.submit(call(coordinator, composition, "same", 2));
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> coordinator.perform(composition, "same", -1));
        }
        assertEquals(1, max.get()); assertEquals(0, locks.size());
    }

    @Test void conflictingIsolationCapacityIsRejected() throws Exception {
        var one = Compositions.<Void, Integer>create().perform((context, input) -> input).isolate("service").concurrently(1).build();
        var two = Compositions.<Void, Integer>create().perform((context, input) -> input).isolate("service").concurrently(2).build();
        try (var coordinator = new Orchestrator()) {
            coordinator.perform(one, null, 1);
            assertThrows(IllegalArgumentException.class, () -> coordinator.perform(two, null, 1));
        }
    }

    @Test void cacheStoresOnlySuccessAndSupportsInvalidationAndNull() throws Exception {
        var store = new MemoryCache(2); var calls = new AtomicInteger();
        var composition = Compositions.<String, Integer>create().perform((context, input) -> {
            if (calls.incrementAndGet() == 1) throw new IOException("first load fails"); return input * 2;
        }).cache(cache -> cache.named("data").key(context -> context)).build();
        try (var coordinator = Orchestrator.builder().cache("data", store).build()) {
            assertThrows(IOException.class, () -> coordinator.perform(composition, "key", 3));
            assertEquals(6, coordinator.perform(composition, "key", 3).output());
            assertEquals(6, coordinator.perform(composition, "key", 3).output()); assertEquals(2, calls.get());
            coordinator.cache("data").invalidate("key"); coordinator.perform(composition, "key", 3); assertEquals(3, calls.get());
            coordinator.cache("data").invalidateAll(); assertEquals(0, store.size());
        }
        store.put("null", null, Duration.ofSeconds(1)); assertTrue(store.get("null").isPresent()); assertNull(store.get("null").orElseThrow().output());
        assertThrows(IllegalArgumentException.class, () -> Compositions.<Void, Integer>create().perform((context, input) -> input)
                .cache(cache -> cache.key(context -> "key")).compensateWith((context, input, output) -> {}));
    }

    @Test void cacheIsBoundedAndExpires() throws Exception {
        var store = new MemoryCache(2);
        store.put("a", 1, Duration.ofSeconds(1)); store.put("b", 2, Duration.ofSeconds(1)); store.get("a");
        store.put("c", 3, Duration.ofSeconds(1)); assertTrue(store.get("b").isEmpty()); assertEquals(2, store.size());
        store.put("short", 4, Duration.ofMillis(20)); Thread.sleep(30); assertTrue(store.get("short").isEmpty());
    }

    @Test void cancellingOneCacheSubscriberKeepsTheSharedLoaderAlive() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var waits = new AtomicInteger(); var calls = new AtomicInteger();
        var firstId = new AtomicReference<String>();
        var composition = Compositions.<String, Integer>create().perform((context, input) -> {
            calls.incrementAndGet(); entered.countDown(); ready(release); return input * 2;
        }).cache(cache -> cache.key(context -> context)).build();
        try (var coordinator = Orchestrator.builder().events(event -> {
            if (event.type().equals("STARTED") && event.kind().equals("EXECUTION")) firstId.compareAndSet(null, event.executionId());
            if (event.type().equals("CACHE_WAIT")) waits.incrementAndGet();
        }).build(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(call(coordinator, composition, "same", 3)); ready(entered);
            var second = executor.submit(call(coordinator, composition, "same", 3)); until(() -> waits.get() == 2);
            try {
                assertTrue(coordinator.cancel(firstId.get()));
                assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            assertEquals(6, second.get(2, TimeUnit.SECONDS).output()); assertEquals(1, calls.get());
        }
    }

    @Test void shutdownRejectsNewExecutionsAndCancelsActiveWork() throws Exception {
        var entered = new CountDownLatch(1);
        var composition = Compositions.<Void, Integer>create().perform((context, input) -> {
            entered.countDown(); new CountDownLatch(1).await(); return input;
        }).build();
        try (var coordinator = new Orchestrator(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(call(coordinator, composition, null, 1)); ready(entered);
            var report = coordinator.shutdown(Duration.ZERO);
            var failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertEquals(FailureKind.SHUTDOWN, Failures.details(failure).orElseThrow().kind());
            assertTrue(report.complete()); assertThrows(ShutdownException.class, () -> coordinator.perform(composition, null, 2));
        }
    }

    @Test void historyHasHierarchyCorrelationAndExplicitAttributesOnly() throws Exception {
        var history = new MemoryHistory(8, 100, Duration.ofMinutes(1));
        var composition = Compositions.<String, Integer>create().named("order").perform((context, input) -> input + 1).named("validate").build();
        try (var coordinator = Orchestrator.builder().events(history).build()) {
            var execution = coordinator.perform(composition, "secret", 1, new ExecutionOptions("request-1", Map.of("tenant", "a")));
            var recorded = history.find(execution.id()).orElseThrow(); assertFalse(recorded.incomplete());
            assertTrue(recorded.events().stream().allMatch(event -> "request-1".equals(event.correlationId())));
            assertTrue(recorded.events().stream().allMatch(event -> Map.of("tenant", "a").equals(event.attributes())));
            assertTrue(recorded.events().stream().anyMatch(event -> event.name().equals("validate") && event.parentSpanId() != null));
            history.remove(execution.id()); assertTrue(history.find(execution.id()).isEmpty());
        }
    }

    @Test void boundedHistoryMarksLossAndEvictsOldExecutions() throws Exception {
        var history = new MemoryHistory(1, 1, Duration.ofMinutes(1));
        var composition = Compositions.<Void, Integer>create().perform((context, input) -> input).build();
        try (var coordinator = Orchestrator.builder().events(history).build()) {
            var first = coordinator.perform(composition, null, 1); assertTrue(history.find(first.id()).orElseThrow().incomplete());
            var second = coordinator.perform(composition, null, 2); assertTrue(history.find(first.id()).isEmpty()); assertTrue(history.find(second.id()).isPresent());
        }
    }

    @Test void asyncExporterCountsDropsAndMarksHistoryIncomplete() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var history = new MemoryHistory(8, 100, Duration.ofMinutes(1));
        try (var exporter = new AsyncEventListener(event -> { entered.countDown(); stubborn(release); }, 1,
                AsyncEventListener.OverflowPolicy.DROP, history::markIncomplete)) {
            var event = new ExecutionEvent("id", null, "span", null, "task", "TASK", "STARTED", java.time.Instant.now(), Duration.ZERO, null, Map.of());
            exporter.onEvent(event); ready(entered);
            try {
                exporter.onEvent(event); exporter.onEvent(event);
                assertEquals(1, exporter.dropped()); assertTrue(history.find("id").orElseThrow().incomplete());
            } finally { release.countDown(); }
        }
    }

    @Test void nestedDeadlineAndWaitForStopBeforeFollowingTasks() {
        var calls = new AtomicInteger();
        var child = Compositions.<Void, Integer>create().waitFor(Duration.ofSeconds(1)).build().within(Duration.ofMillis(40));
        var composition = Compositions.<Void, Integer>create().perform(child)
                .perform((context, input) -> calls.incrementAndGet()).build();
        try (var coordinator = new Orchestrator()) {
            var error = assertThrows(TimeoutException.class, () -> coordinator.perform(composition, null, 1));
            assertEquals(FailureKind.TIMEOUT, Failures.details(error).orElseThrow().kind()); assertEquals(0, calls.get());
        }
    }
}
