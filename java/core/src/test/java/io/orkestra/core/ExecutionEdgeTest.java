package io.orkestra.core;

import io.orkestra.core.Failures;
import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.composition.TaskRef;
import io.orkestra.core.execution.FailureKind;
import io.orkestra.core.policy.RetryPolicy;
import io.orkestra.core.spi.ExecutionEvent;
import io.orkestra.core.task.Compensation;
import io.orkestra.core.task.Task;
import io.orkestra.core.local.cache.MemoryCache;
import io.orkestra.core.local.events.AsyncEventListener;
import io.orkestra.core.local.history.MemoryHistory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.orkestra.core.WorkflowTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ExecutionEdgeTest {
    @Test void timedOutOwnerRetainsCapacityUntilItsBodyReallyStops() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var active = new AtomicInteger(); var max = new AtomicInteger(); var calls = new AtomicInteger();
        var task = TaskRef.<Void, Integer, Integer>of((context, input) -> {
            calls.incrementAndGet(); max.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try { stubborn(release); return input; } finally { active.decrementAndGet(); }
        }).concurrently(1);
        var definition = Compositions.<Void, Integer>create().perform(task).build();
        try (var coordinator = Orchestrator.builder().cancellationBudget(Duration.ofMillis(15)).build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var owner = executor.submit(call(coordinator, definition.within(Duration.ofMillis(150)), null, 1));
            try {
                ready(entered);
                var error = assertThrows(ExecutionException.class, () -> owner.get(2, TimeUnit.SECONDS));
                assertTrue(Failures.details(error).orElseThrow().workPending());
                assertThrows(TimeoutException.class, () -> coordinator.perform(definition.within(Duration.ofMillis(40)), null, 2));
                assertEquals(1, calls.get());
            } finally { release.countDown(); }
            until(() -> active.get() == 0);
            assertEquals(3, coordinator.perform(definition, null, 3).output()); assertEquals(1, max.get());
        }
    }

    @Test void lastCacheSubscriberCancellationDoesNotCacheAnAbandonedLoad() throws Exception {
        var store = new MemoryCache(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1); var calls = new AtomicInteger();
        var definition = Compositions.<String, Integer>create().perform((context, input) -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown(); stubborn(release); finished.countDown();
            }
            return input;
        }).cache(cache -> cache.named("data").key(context -> context)).build();
        try (var coordinator = Orchestrator.builder().cache("data", store).cancellationBudget(Duration.ofMillis(15)).build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var abandoned = executor.submit(call(coordinator, definition.within(Duration.ofMillis(150)), "key", 1));
            try { ready(entered); assertThrows(ExecutionException.class, () -> abandoned.get(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            ready(finished);
            assertEquals(2, coordinator.perform(definition, "key", 2).output()); assertEquals(2, calls.get());
        }
    }

    @Test void compensationBudgetReportsItsOwnPendingWork() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        Task<Void, Integer, Integer> fail = (context, input) -> { throw new IllegalArgumentException("failure"); };
        var definition = Compositions.<Void, Integer>create().withCompensation(group -> group
                .perform((context, input) -> input)
                .compensateWith((context, input, output) -> { entered.countDown(); stubborn(release); })
                .perform(fail)).build();
        try (var coordinator = Orchestrator.builder().compensationBudget(Duration.ofMillis(50)).build()) {
            try {
                var error = assertThrows(IllegalArgumentException.class, () -> coordinator.perform(definition, null, 1));
                ready(entered);
                var details = Failures.details(error).orElseThrow();
                assertTrue(details.compensationFailed()); assertTrue(details.workPending());
                assertFalse(details.compensationErrors().isEmpty());
                assertInstanceOf(TimeoutException.class, details.compensationErrors().getFirst());
                assertTrue(coordinator.shutdown(Duration.ZERO).pendingCompensations().contains(details.executionId()));
            } finally { release.countDown(); }
        }
    }

    @Test void shutdownReportsWorkThatIgnoresInterruption() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
        var definition = Compositions.<Void, Integer>create().perform((context, input) -> {
            entered.countDown(); stubborn(release); return input;
        }).build();
        try (var coordinator = Orchestrator.builder().cancellationBudget(Duration.ofMillis(15)).compensationBudget(Duration.ofMillis(30)).build();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(call(coordinator, definition, null, 1));
            try {
                ready(entered); var report = coordinator.shutdown(Duration.ZERO);
                assertFalse(report.complete()); assertTrue(report.pendingWork() > 0);
                var error = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
                assertEquals(FailureKind.SHUTDOWN, Failures.details(error).orElseThrow().kind());
                assertTrue(report.pendingExecutions().contains(Failures.details(error).orElseThrow().executionId()));
            } finally { release.countDown(); }
        }
    }

    @Test void invalidNamedCacheAndNonPositiveSettingsFailClearly() {
        var definition = Compositions.<String, Integer>create().perform((context, input) -> input)
                .cache(cache -> cache.named("absent").key(context -> context)).build();
        try (var coordinator = new Orchestrator()) {
            assertThrows(IllegalArgumentException.class, () -> coordinator.perform(definition, "key", 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new MemoryCache(0));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy.Builder().upTo(0).build());
        assertThrows(IllegalArgumentException.class, () -> Compositions.<Void, Integer>create().within(Duration.ZERO));
    }

    @Test void sameParticipationCannotSilentlyChangeCapacityAcrossExecutions() throws Exception {
        var reference = TaskRef.<Void, Integer, Integer>of((context, input) -> input);
        var first = Compositions.<Void, Integer>create().perform(reference.concurrently(1)).build();
        var second = Compositions.<Void, Integer>create().perform(reference.concurrently(2)).build();
        try (var coordinator = new Orchestrator()) {
            coordinator.perform(first, null, 1);
            assertThrows(IllegalArgumentException.class, () -> coordinator.perform(second, null, 2));
        }
    }

    @Test void observerFailuresAreCountedAndDoNotFailDefaultExecution() throws Exception {
        var history = new MemoryHistory(4, 100, Duration.ofMinutes(1));
        var definition = Compositions.<Void, Integer>create().perform((context, input) -> input + 1).build();
        try (var coordinator = Orchestrator.builder().events(event -> { throw new IllegalStateException("sink unavailable"); }).events(history).build()) {
            var result = coordinator.perform(definition, null, 1);
            assertEquals(2, result.output()); assertTrue(coordinator.eventFailures() > 0);
            assertTrue(history.find(result.id()).orElseThrow().incomplete());
        }
    }

    @Test void strictAsyncQueueOverflowFailsBeforeBusinessWorkStarts() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        try (var exporter = new AsyncEventListener(event -> { entered.countDown(); stubborn(release); }, 1,
                AsyncEventListener.OverflowPolicy.FAIL, id -> {})) {
            var event = new ExecutionEvent("id", null, "span", null, "task", "TASK", "STARTED", Instant.now(), Duration.ZERO, null, Map.of());
            exporter.onEvent(event); ready(entered); exporter.onEvent(event);
            var definition = Compositions.<Void, Integer>create().perform((context, input) -> calls.incrementAndGet()).build();
            try (var coordinator = Orchestrator.builder().events(exporter).strictEvents(true).build()) {
                try { assertThrows(IllegalStateException.class, () -> coordinator.perform(definition, null, 1)); assertEquals(0, calls.get()); }
                finally { release.countDown(); }
            }
        }
    }

    @Test void exporterCloseIsBoundedAndMarksAnInFlightExportIncomplete() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var history = new MemoryHistory(4, 10, Duration.ofMinutes(1));
        try (var exporter = new AsyncEventListener(event -> { entered.countDown(); stubborn(release); }, 1,
                AsyncEventListener.OverflowPolicy.DROP, history::markIncomplete)) {
            exporter.onEvent(new ExecutionEvent("id", null, "span", null, "task", "TASK", "STARTED", Instant.now(), Duration.ZERO, null, Map.of()));
            ready(entered);
            try { assertFalse(exporter.close(Duration.ofMillis(10))); assertTrue(history.find("id").orElseThrow().incomplete()); }
            finally { release.countDown(); }
        }
    }

    @Test void historyTtlAndPartialHistoryAreExplicit() throws Exception {
        var history = new MemoryHistory(4, 10, Duration.ofMillis(20));
        history.onEvent(new ExecutionEvent("id", null, "id", null, "execution", "EXECUTION", "STARTED", Instant.now(), Duration.ZERO, null, Map.of()));
        Thread.sleep(30); assertTrue(history.find("id").isEmpty());
        history.onEvent(new ExecutionEvent("id", null, "span", "id", "task", "TASK", "FAILED", Instant.now(), Duration.ZERO, null, Map.of()));
        assertTrue(history.find("id").orElseThrow().incomplete());
    }

    @Test void forEachFailuresPreventFollowingStepsAndJoinFailuresPropagate() {
        var following = new AtomicInteger();
        var iteration = Compositions.<Void, List<Integer>>create().forEach(items -> items, item -> item.concurrently(2)
                .perform((context, input) -> { if (input < 0) throw new IllegalArgumentException("bad item"); return input; }))
                .perform((context, input) -> following.incrementAndGet()).build();
        var join = Compositions.<Void, Integer>create().parallel(group -> group.perform((context, input) -> input))
                .join((context, results) -> { throw new IllegalStateException("bad join"); }).build();
        try (var coordinator = new Orchestrator()) {
            assertThrows(IllegalArgumentException.class, () -> coordinator.perform(iteration, null, List.of(1, -1, 2)));
            assertEquals(0, following.get());
            assertThrows(IllegalStateException.class, () -> coordinator.perform(join, null, 1));
        }
    }

    @Test void sharedCacheFailureHasDistinctExecutionDiagnostics() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var waits = new AtomicInteger(); var calls = new AtomicInteger();
        var ids = ConcurrentHashMap.<String>newKeySet();
        var shared = new java.io.IOException("shared load failed");
        Task<String, Integer, Integer> task = (context, input) -> {
            calls.incrementAndGet(); entered.countDown(); ready(release); throw shared;
        };
        var definition = Compositions.<String, Integer>create().perform(task).cache(cache -> cache.key(context -> context)).build();
        try (var coordinator = Orchestrator.builder().events(event -> {
            if (event.type().equals("STARTED") && event.kind().equals("EXECUTION")) ids.add(event.executionId());
            if (event.type().equals("CACHE_WAIT")) waits.incrementAndGet();
        }).build(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(call(coordinator, definition, "same", 1)); ready(entered);
            var second = executor.submit(call(coordinator, definition, "same", 1));
            try { until(() -> waits.get() == 2); } finally { release.countDown(); }
            var one = assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS)).getCause();
            var two = assertThrows(ExecutionException.class, () -> second.get(2, TimeUnit.SECONDS)).getCause();
            String oneId = Failures.details(one).orElseThrow().executionId();
            String twoId = Failures.details(two).orElseThrow().executionId();
            assertNotEquals(oneId, twoId); assertEquals(ids, Set.of(oneId, twoId)); assertEquals(1, calls.get());
        }
    }

    @Test void unsupportedGroupCacheAndRetryAreRejectedAtDefinitionTime() {
        var child = Compositions.<Void, Integer>create().perform((context, input) -> input).build();
        assertThrows(IllegalArgumentException.class, () -> Compositions.<Void, Integer>create()
                .perform(child).retry(retry -> retry.upTo(2)));
        assertThrows(IllegalArgumentException.class, () -> TaskRef.of(child).cache(cache -> cache.key(context -> "key")));
    }

    @Test void globalAndTaskTimeoutsUseTheHandlerOfTheOwningScope() {
        var taskHandlers = new AtomicInteger(); var compositionHandlers = new AtomicInteger();
        Task<Void, Integer, Integer> blocked = (context, input) -> { new CountDownLatch(1).await(); return input; };
        var global = Compositions.<Void, Integer>create().perform(blocked)
                .onFailure((context, cause) -> { taskHandlers.incrementAndGet(); return new IllegalArgumentException("task", cause); })
                .build().within(Duration.ofMillis(70))
                .onFailure((context, cause) -> { compositionHandlers.incrementAndGet(); return new IllegalStateException("global", cause); });
        var local = Compositions.<Void, Integer>create().perform(blocked).within(Duration.ofMillis(40))
                .onFailure((context, cause) -> { taskHandlers.incrementAndGet(); return new IllegalArgumentException("task", cause); })
                .build().within(Duration.ofSeconds(1))
                .onFailure((context, cause) -> { compositionHandlers.incrementAndGet(); return new IllegalStateException("global", cause); });
        try (var coordinator = new Orchestrator()) {
            assertThrows(IllegalStateException.class, () -> coordinator.perform(global, null, 1));
            assertEquals(0, taskHandlers.get()); assertEquals(1, compositionHandlers.get());
            assertThrows(IllegalArgumentException.class, () -> coordinator.perform(local, null, 1));
            assertEquals(1, taskHandlers.get()); assertEquals(1, compositionHandlers.get());
        }
    }

    @Test void compilerRequiresJoinAndTypedCompensation(@TempDir Path directory) throws Exception {
        assertInvalid(directory, "MissingJoin", """
                Compositions.<Void, Integer>create().parallel(group ->
                    group.perform((context, input) -> input)).build();
                """);
        assertInvalid(directory, "WrongCompensation", """
                Task<Void, Integer, String> task = (context, input) -> input.toString();
                Compensation<Void, String, String> wrong = (context, input, output) -> {};
                Compositions.<Void, Integer>create().perform(task).compensateWith(wrong).build();
                """);
    }
    private void assertInvalid(Path directory, String name, String body) throws Exception {
        Path source = directory.resolve(name + ".java");
        Files.writeString(source, "import io.orkestra.core.composition.*; import io.orkestra.core.task.*; import io.orkestra.core.policy.*; import io.orkestra.core.execution.*; import io.orkestra.core.exception.*; import io.orkestra.core.*; class " + name + " { void define() { " + body + " } }");
        var compiler = ToolProvider.getSystemJavaCompiler(); var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            String classpath = Path.of(Compositions.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            assertFalse(compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "25", "-classpath", classpath, "-d", directory.toString()), null, manager.getJavaFileObjects(source)).call());
            assertTrue(diagnostics.getDiagnostics().stream().anyMatch(error -> error.getKind() == javax.tools.Diagnostic.Kind.ERROR));
        }
    }
}
