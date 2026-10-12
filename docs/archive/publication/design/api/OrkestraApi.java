package io.orkestra.design;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;

/** API proposal only: no execution engine or production implementation. */
public final class OrkestraApi {
    private OrkestraApi() {}

    @FunctionalInterface
    public interface Task<C, I, O> {
        O execute(C context, I input) throws Exception;
    }

    @FunctionalInterface
    public interface Join<C, O> {
        O combine(C context, Results results) throws Exception;
    }

    /** Identity of one participation, distinct from the reusable task. */
    public static final class TaskRef<C, I, O> {
        private final Task<C, I, O> task;
        private final String name;

        private TaskRef(Task<C, I, O> task, String name) {
            this.task = Objects.requireNonNull(task);
            this.name = name;
        }

        public static <C, I, O> TaskRef<C, I, O> of(Task<C, I, O> task) {
            return new TaskRef<>(task, null);
        }

        /** Returns a new reference; configure it before adding it to a group. */
        public TaskRef<C, I, O> named(String name) {
            if (Objects.requireNonNull(name).isBlank()) {
                throw new IllegalArgumentException("Name must not be blank");
            }
            return new TaskRef<>(task, name);
        }

        public Task<C, I, O> task() { return task; }
        public String name() { return name; }
    }

    public interface Results {
        /** Resolves a successful output by participation identity, never by name. */
        <C, I, O> O get(TaskRef<C, I, O> reference);
    }

    public interface Composition<C, I, O> {}

    @FunctionalInterface
    public interface ParallelDefinition<C, I> {
        void define(ParallelGroup<C, I> group);
    }

    public interface ParallelGroup<C, I> {
        ParallelGroup<C, I> named(String name);
        ParallelGroup<C, I> concurrently(int limit);
        ParallelGroup<C, I> within(Duration duration);
        <O> ParallelGroup<C, I> perform(Task<C, I, O> task);
        <O> ParallelGroup<C, I> perform(TaskRef<C, I, O> reference);
        <O> ParallelGroup<C, I> perform(Composition<C, I, O> composition);
    }

    /** Parallel awaits every branch; join must define the next pipeline input. */
    public interface AfterParallel<C, I> {
        <O> Builder<C, I, O> join(Join<C, O> join);
    }

    /** I is the composition's initial input; Current is the previous output. */
    public interface Builder<C, I, Current> {
        Builder<C, I, Current> named(String name);
        Builder<C, I, Current> within(Duration duration);
        <O> Builder<C, I, O> perform(Task<C, Current, O> task);
        <O> Builder<C, I, O> perform(Composition<C, Current, O> composition);
        AfterParallel<C, I> parallel(ParallelDefinition<C, Current> definition);
        Composition<C, I, Current> build();
    }

    /** Successful execution; duration is measured using a monotonic clock. */
    public record Execution<O>(
            String id,
            Instant startedAt,
            Instant completedAt,
            Duration duration,
            O output) {
        public Execution {
            Objects.requireNonNull(id);
            Objects.requireNonNull(startedAt);
            Objects.requireNonNull(completedAt);
            Objects.requireNonNull(duration);
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Duration must not be negative");
            }
        }
    }

    public enum FailureKind {
        TASK_FAILED, TIMEOUT, CANCELLED, SHUTDOWN
    }

    /** Partial-failure diagnostics; original failures remain in the cause chain. */
    public record FailureDetails(
            String executionId,
            FailureKind kind,
            boolean compensationFailed,
            boolean workPending) {
        public FailureDetails {
            Objects.requireNonNull(executionId);
            Objects.requireNonNull(kind);
        }
    }

    @FunctionalInterface
    public interface FailureHandler<C> {
        Exception translate(C context, Throwable cause) throws Exception;
    }

    public interface Orchestrator {
        <C, I, O> Execution<O> perform(
                Composition<C, I, O> composition,
                C context,
                I input) throws Exception;
    }

    /** Extracts library diagnostics attached to the thrown exception or its causes. */
    public interface Failures {
        Optional<FailureDetails> details(Throwable error);
    }

    public interface Compositions {
        <C, I> Builder<C, I, I> create();
    }
}
