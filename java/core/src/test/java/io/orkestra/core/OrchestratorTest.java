package io.orkestra.core;

import io.orkestra.core.Failures;
import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Composition;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.exception.ExecutionFailureException;
import io.orkestra.core.execution.Execution;
import io.orkestra.core.execution.FailureKind;
import io.orkestra.core.task.Task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorTest {
    private final Orchestrator orchestrator = new Orchestrator();

    record Request(int quantity) {}
    record Validated(int quantity) {}
    record Receipt(String value) {}

    @AfterEach
    void clearInterruption() {
        Thread.interrupted();
        orchestrator.close();
    }

    @Test
    void chainsDifferentTypesAndSharesApplicationContext() throws Exception {
        var trace = new ArrayList<String>();
        var composition = Compositions.<List<String>, Request>create()
                .named("place-order")
                .perform((context, input) -> {
                    context.add("validate");
                    return new Validated(input.quantity());
                })
                .perform((context, input) -> {
                    context.add("confirm");
                    return new Receipt("confirmed:" + input.quantity());
                })
                .build();

        Execution<Receipt> result = orchestrator.perform(composition, trace, new Request(3));

        assertEquals(new Receipt("confirmed:3"), result.output());
        assertEquals(List.of("validate", "confirm"), trace);
        assertEquals("place-order", composition.name().orElseThrow());
        assertFalse(result.id().isBlank());
        assertFalse(result.duration().isNegative());
        assertNotNull(result.startedAt());
        assertNotNull(result.completedAt());
    }

    @Test
    void nestedCompositionSuppliesTheNextStepsInput() throws Exception {
        var child = Compositions.<String, Integer>create()
                .perform((context, input) -> context + input)
                .build();
        var parent = Compositions.<String, Integer>create()
                .perform(child)
                .perform((context, input) -> input.length())
                .build();

        assertEquals(4, orchestrator.perform(parent, "qty", 2).output());
    }

    @Test
    void buildersAndDefinitionsCanBeReusedWithoutMutation() throws Exception {
        var base = Compositions.<Void, Integer>create();
        var incremented = base.perform((context, input) -> input + 1).build();
        var rendered = base.perform((context, input) -> "value:" + input).build();

        assertEquals(2, orchestrator.perform(incremented, null, 1).output());
        assertEquals(6, orchestrator.perform(incremented, null, 5).output());
        assertEquals("value:1", orchestrator.perform(rendered, null, 1).output());
        assertEquals(1, orchestrator.perform(base.build(), null, 1).output());
        assertTrue(base.build().name().isEmpty());
        assertEquals("named", base.named("named").build().name().orElseThrow());
        assertTrue(base.build().name().isEmpty());
    }

    @Test
    void reusedDefinitionKeepsConcurrentInputsAndExecutionIdsIndependent() throws Exception {
        var composition = Compositions.<Void, Integer>create()
                .perform((context, input) -> input * 2)
                .build();
        var work = new ArrayList<Callable<Execution<Integer>>>();
        for (int i = 0; i < 24; i++) {
            int input = i;
            work.add(() -> orchestrator.perform(composition, null, input));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(work);
            var ids = new HashSet<String>();
            for (int i = 0; i < results.size(); i++) {
                var execution = results.get(i).get();
                assertEquals(i * 2, execution.output());
                assertTrue(ids.add(execution.id()));
            }
        }
    }

    @Test
    void failureStopsThePipelineAndPreservesTheExceptionInstance() {
        var original = new Exception("cannot validate");
        var subsequent = new AtomicInteger();
        Task<Void, Integer, Integer> failed = (context, input) -> { throw original; };
        var composition = Compositions.<Void, Integer>create()
                .perform(failed)
                .perform((context, input) -> subsequent.incrementAndGet())
                .build();

        var thrown = assertThrows(Exception.class, () -> orchestrator.perform(composition, null, 1));

        assertSame(original, thrown);
        assertEquals(0, subsequent.get());
        var details = Failures.details(thrown).orElseThrow();
        assertFalse(details.executionId().isBlank());
        assertEquals(FailureKind.TASK_FAILED, details.kind());
        assertFalse(details.workPending());
        assertFalse(details.compensationFailed());
    }

    @Test
    void translatesToAnApplicationExceptionWithoutResuming() {
        var original = new Exception("storage failed");
        var following = new AtomicInteger();
        Task<String, Integer, Integer> task = (context, input) -> { throw original; };
        var composition = Compositions.<String, Integer>create()
                .perform(task)
                .perform((context, input) -> following.incrementAndGet())
                .build()
                .onFailure((context, cause) -> new IllegalArgumentException(context, cause));

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> orchestrator.perform(composition, "application failure", 1));

        assertEquals("application failure", thrown.getMessage());
        assertSame(original, thrown.getCause());
        assertEquals(0, following.get());
        assertTrue(Failures.details(thrown).isPresent());
    }

    @Test
    void handlerDoesNotMutateTheOriginalDefinition() {
        var original = new Exception("failure");
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var definition = Compositions.<Void, Integer>create().perform(task).build();
        var translated = definition.onFailure((context, cause) -> new IllegalStateException("translated"));

        assertSame(original, assertThrows(Exception.class,
                () -> orchestrator.perform(definition, null, 1)));
        var thrown = assertThrows(IllegalStateException.class,
                () -> orchestrator.perform(translated, null, 1));
        assertSame(original, thrown.getSuppressed()[0]);
    }

    @Test
    void nearestCompositionHandlerTranslatesOnlyOnce() {
        var original = new Exception("child failed");
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var outerCalls = new AtomicInteger();
        var child = Compositions.<Void, Integer>create().perform(task).build()
                .onFailure((context, cause) -> new IllegalArgumentException("child", cause));
        var parent = Compositions.<Void, Integer>create().perform(child).build()
                .onFailure((context, cause) -> {
                    outerCalls.incrementAndGet();
                    return new IllegalStateException("parent", cause);
                });

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> orchestrator.perform(parent, null, 1));
        assertEquals("child", thrown.getMessage());
        assertEquals(0, outerCalls.get());
        assertSame(original, thrown.getCause());
    }

    @Test
    void outerHandlerTranslatesAnUnhandledNestedFailure() {
        var original = new Exception("child failed");
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var child = Compositions.<Void, Integer>create().perform(task).build();
        var parent = Compositions.<Void, Integer>create().perform(child).build()
                .onFailure((context, cause) -> new IllegalStateException("parent", cause));

        var thrown = assertThrows(IllegalStateException.class,
                () -> orchestrator.perform(parent, null, 1));
        assertSame(original, thrown.getCause());
    }

    @Test
    void failingHandlerPreservesItsExceptionAndTheOriginalFailure() {
        var original = new Exception("task failure");
        var handlerError = new Exception("handler failure");
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var composition = Compositions.<Void, Integer>create().perform(task).build()
                .onFailure((context, cause) -> { throw handlerError; });

        var thrown = assertThrows(Exception.class, () -> orchestrator.perform(composition, null, 1));
        assertSame(handlerError, thrown);
        assertSame(original, thrown.getSuppressed()[0]);
        assertTrue(Failures.details(thrown).isPresent());
    }

    @Test
    void interruptedHandlerPreservesTheInterruptSignal() {
        Task<Void, Integer, Integer> task = (context, input) -> { throw new Exception("failure"); };
        var composition = Compositions.<Void, Integer>create().perform(task).build()
                .onFailure((context, cause) -> { throw new InterruptedException("handler interrupted"); });

        var thrown = assertThrows(InterruptedException.class,
                () -> orchestrator.perform(composition, null, 1));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(FailureKind.CANCELLED, Failures.details(thrown).orElseThrow().kind());
    }

    @Test
    void nullHandlerResultIsAFailureWithTheOriginalCausePreserved() {
        var original = new Exception("failure");
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var composition = Compositions.<Void, Integer>create().perform(task).build()
                .onFailure((context, cause) -> null);

        var thrown = assertThrows(NullPointerException.class,
                () -> orchestrator.perform(composition, null, 1));
        assertSame(original, thrown.getSuppressed()[0]);
    }

    @Test
    void interruptionRestoresTheFlagAndStopsFurtherTasks() {
        var following = new AtomicInteger();
        Task<Void, Integer, Integer> interrupted = (context, input) -> {
            throw new InterruptedException("cancelled");
        };
        var composition = Compositions.<Void, Integer>create().perform(interrupted)
                .perform((context, input) -> following.incrementAndGet()).build();

        var thrown = assertThrows(InterruptedException.class,
                () -> orchestrator.perform(composition, null, 1));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(0, following.get());
        assertEquals(FailureKind.CANCELLED, Failures.details(thrown).orElseThrow().kind());
    }

    @Test
    void translatedInterruptionRetainsCancellationDiagnostics() {
        Task<Void, Integer, Integer> interrupted = (context, input) -> {
            throw new InterruptedException("cancelled");
        };
        var composition = Compositions.<Void, Integer>create().perform(interrupted).build()
                .onFailure((context, cause) -> new IllegalStateException("cancelled", cause));

        var thrown = assertThrows(IllegalStateException.class,
                () -> orchestrator.perform(composition, null, 1));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(FailureKind.CANCELLED, Failures.details(thrown).orElseThrow().kind());
    }

    @Test
    void preInterruptedThreadDoesNotStartAnyTask() {
        var calls = new AtomicInteger();
        var composition = Compositions.<Void, Integer>create()
                .perform((context, input) -> calls.incrementAndGet()).build();
        Thread.currentThread().interrupt();

        assertThrows(InterruptedException.class, () -> orchestrator.perform(composition, null, 1));
        assertEquals(0, calls.get());
    }

    @Test
    void fatalErrorsRemainUntranslated() {
        var fatal = new AssertionError("fatal");
        var handlers = new AtomicInteger();
        Task<Void, Integer, Integer> task = (context, input) -> { throw fatal; };
        var composition = Compositions.<Void, Integer>create().perform(task).build()
                .onFailure((context, cause) -> {
                    handlers.incrementAndGet();
                    return new Exception("translated");
                });

        assertSame(fatal, assertThrows(AssertionError.class,
                () -> orchestrator.perform(composition, null, 1)));
        assertEquals(0, handlers.get());
    }

    @Test
    void diagnosticsCanBeFoundThroughWrapperAndSuppressedExceptions() {
        var original = new Exception("failure");
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var composition = Compositions.<Void, Integer>create().perform(task).build();
        assertThrows(Exception.class, () -> orchestrator.perform(composition, null, 1));
        var details = Failures.details(original).orElseThrow();

        assertEquals(details, Failures.details(new Exception("wrapper", original)).orElseThrow());
        var suppressedWrapper = new Exception("wrapper");
        suppressedWrapper.addSuppressed(original);
        assertEquals(details, Failures.details(suppressedWrapper).orElseThrow());
        assertTrue(Failures.details(null).isEmpty());
        assertTrue(Failures.details(new Exception("unrelated")).isEmpty());
    }

    @Test
    void diagnosticSearchToleratesCyclesAndKeepsReusedOccurrencesDistinct() {
        var original = new Exception("reused");
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var composition = Compositions.<Void, Integer>create().perform(task).build();
        assertThrows(Exception.class, () -> orchestrator.perform(composition, null, 1));
        var first = Failures.details(original).orElseThrow();
        var second = assertThrows(ExecutionFailureException.class, () -> orchestrator.perform(composition, null, 2));
        assertSame(original, second.getCause());
        assertNotEquals(first.executionId(), Failures.details(second).orElseThrow().executionId());
        assertEquals(first.executionId(), Failures.details(original).orElseThrow().executionId());

        var one = new Exception("one");
        var two = new Exception("two");
        one.initCause(two);
        two.initCause(one);
        assertTrue(Failures.details(one).isEmpty());
    }

    @Test
    void suppressionDisabledExceptionsUseADiagnosticWrapper() {
        var original = new Exception("no suppression", null, false, false) {};
        Task<Void, Integer, Integer> task = (context, input) -> { throw original; };
        var composition = Compositions.<Void, Integer>create().perform(task).build();

        var thrown = assertThrows(ExecutionFailureException.class,
                () -> orchestrator.perform(composition, null, 1));
        assertSame(original, thrown.getCause());
        assertTrue(Failures.details(thrown).isPresent());
    }

    @Test
    void validatesDefinitionArgumentsAndAllowsExplicitNullOutputs() throws Exception {
        var builder = Compositions.<Void, String>create();
        assertThrows(IllegalArgumentException.class, () -> builder.named(" "));
        assertThrows(NullPointerException.class, () -> builder.named(null));
        assertThrows(NullPointerException.class, () -> builder.perform((Task<Void, String, String>) null));
        assertThrows(NullPointerException.class, () -> builder.perform((Composition<Void, String, String>) null));
        assertThrows(NullPointerException.class, () -> builder.build().onFailure(null));
        assertThrows(NullPointerException.class, () -> orchestrator.perform(null, null, null));
        var nullable = builder.<String>perform((context, input) -> null).build();
        assertNull(orchestrator.perform(nullable, null, "input").output());
    }

    @Test
    void rejectsInvalidExecutionMetadata() {
        var now = Instant.now();
        assertThrows(IllegalArgumentException.class,
                () -> new Execution<>(" ", now, now, Duration.ZERO, "output"));
        assertThrows(IllegalArgumentException.class,
                () -> new Execution<>("id", now, now, Duration.ofNanos(-1), "output"));
    }

    @Test
    void incompatibleNextInputIsRejectedByTheJavaCompiler(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("InvalidComposition.java");
        Files.writeString(source, """
                import io.orkestra.core.composition.*; import io.orkestra.core.task.*; import io.orkestra.core.policy.*; import io.orkestra.core.execution.*; import io.orkestra.core.exception.*; import io.orkestra.core.*;
                class InvalidComposition {
                    void define() {
                        Task<Void, Integer, String> first = (context, input) -> input.toString();
                        Task<Void, Integer, Integer> wrong = (context, input) -> input + 1;
                        Compositions.<Void, Integer>create().perform(first).perform(wrong).build();
                    }
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Tests require a JDK, not a JRE");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        String classpath = Path.of(Compositions.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "25", "-classpath", classpath, "-d", directory.toString()),
                    null, manager.getJavaFileObjects(source)).call();
            assertFalse(compiled);
            assertTrue(diagnostics.getDiagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR));
        }
    }
}
