package io.orkestra.core;

import io.orkestra.core.composition.Composition;
import io.orkestra.core.exception.ExecutionFailureException;
import io.orkestra.core.exception.ShutdownException;
import io.orkestra.core.execution.Execution;
import io.orkestra.core.execution.ExecutionOptions;
import io.orkestra.core.execution.FailureDetails;
import io.orkestra.core.execution.FailureKind;
import io.orkestra.core.execution.ShutdownReport;
import io.orkestra.core.internal.Policy;
import io.orkestra.core.spi.CacheStore;
import io.orkestra.core.spi.CircuitBreaker;
import io.orkestra.core.spi.EventListener;
import io.orkestra.core.spi.HistoryStore;
import io.orkestra.core.spi.LockProvider;
import io.orkestra.core.spi.ResourceProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** In-process coordinator, with independently owned state and bounded shutdown. */
public final class Orchestrator implements AutoCloseable {
    private final Runtime runtime;
    private final Map<String, Runtime.Run> active = new ConcurrentHashMap<>();
    private final Duration shutdownGrace;
    private boolean accepting = true;

    public Orchestrator() { this(new Builder()); }
    private Orchestrator(Builder builder) { runtime = new Runtime(builder); shutdownGrace = builder.shutdownGrace; }
    public static Builder builder() { return new Builder(); }

    public <C, I, O> Execution<O> perform(Composition<C, I, O> composition, C context, I input) throws Exception {
        return perform(composition, context, input, ExecutionOptions.defaults());
    }
    @SuppressWarnings("unchecked")
    public <C, I, O> Execution<O> perform(Composition<C, I, O> composition, C context, I input, ExecutionOptions options) throws Exception {
        Objects.requireNonNull(composition); Objects.requireNonNull(options);
        String id = UUID.randomUUID().toString();
        var run = runtime.new Run(id, options);
        synchronized (this) {
            if (!accepting) throw new ShutdownException("Orchestrator is shutting down");
            active.put(id, run);
        }
        long began = System.nanoTime(); Instant started = Instant.now();
        long deadline = composition.definition().policy.timeout == null ? Long.MAX_VALUE : Runtime.deadline(composition.definition().policy.timeout);
        var frame = new Runtime.Frame(runtime, run, deadline, id, null, "EXECUTION", id, false, false);
        boolean interrupted = Thread.currentThread().isInterrupted();
        try {
            frame.event("STARTED", composition.name().orElse("execution"), null);
            frame.check();
            var work = runtime.work(() -> runtime.execute(composition.definition(), context, input, frame), run, null);
            O output = (O) runtime.await(work, frame);
            frame.check(); frame.event("SUCCEEDED", "execution", null);
            return new Execution<>(id, started, Instant.now(), Duration.ofNanos(System.nanoTime() - began), output);
        } catch (Error fatal) {
            run.cleanup = true;
            Thread.interrupted();
            run.cancel(new FlowFailure(new Exception("Fatal execution error", fatal)));
            runtime.quiesce(run, runtime.cancellationBudget);
            frame.event("FAILED", "execution", fatal);
            throw fatal;
        } catch (Exception error) {
            var failure = error instanceof FlowFailure flow ? flow : new FlowFailure(error);
            interrupted |= error instanceof InterruptedException || failure.error instanceof InterruptedException
                    || Thread.currentThread().isInterrupted();
            Thread.interrupted(); run.cleanup = true;
            run.cancel(failure);
            boolean stopped = runtime.quiesce(run, runtime.cancellationBudget);
            if (!stopped && !run.undo.isEmpty()) frame.event("COMPENSATION_PENDING", "execution", failure.error);
            List<Throwable> compensationErrors = stopped ? runtime.compensate(run) : List.of();
            failure.handler(composition.definition().policy.handler, context);
            Exception translated = failure.error;
            if (failure.handler != null) {
                try { translated = Objects.requireNonNull(failure.handler.translate(failure.context, failure.error), "Failure handler must return an exception"); }
                catch (Exception handlerFailure) { translated = handlerFailure; }
                if (translated instanceof InterruptedException) interrupted = true;
                preserve(translated, failure.error);
            }
            for (var compensationError : compensationErrors) preserve(translated, compensationError);
            boolean pending = run.work.stream().anyMatch(work -> !work.finished());
            FailureKind kind = translated instanceof InterruptedException ? FailureKind.CANCELLED : failure.kind;
            if (kind != FailureKind.TASK_FAILED) frame.event(kind.name(), "execution", failure.error);
            if (pending) {
                frame.event("WORK_PENDING", "execution", failure.error);
                for (var listener : runtime.listeners) if (listener instanceof HistoryStore history) history.markIncomplete(id);
            }
            var details = new FailureDetails(id, kind, !compensationErrors.isEmpty(), pending, compensationErrors);
            Exception result = Failures.attach(translated, details);
            if (!contains(result, failure.error)) {
                var wrapper = new ExecutionFailureException(result, details);
                wrapper.addSuppressed(failure.error); result = wrapper;
            }
            frame.event("FAILED", "execution", result);
            throw result;
        } finally {
            active.remove(id);
            if (interrupted || run.cleanupInterrupted) Thread.currentThread().interrupt();
        }
    }
    private static boolean contains(Throwable container, Throwable expected) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>(); queue.add(container);
        while (!queue.isEmpty()) {
            var next = queue.remove(); if (!seen.add(next)) continue;
            if (next == expected) return true;
            if (next.getCause() != null) queue.add(next.getCause());
            Collections.addAll(queue, next.getSuppressed());
        }
        return false;
    }
    private static void preserve(Throwable target, Throwable original) {
        if (!contains(target, original)) target.addSuppressed(original);
    }
    public boolean cancel(String executionId) {
        var run = active.get(executionId);
        if (run == null) return false;
        run.cancel(new FlowFailure(new java.util.concurrent.CancellationException("Execution cancelled"), FailureKind.CANCELLED));
        return true;
    }
    public long eventFailures() { return runtime.eventFailures.get(); }

    public CacheStore cache(String name) {
        var store = runtime.namedCaches.get(name);
        if (store == null) throw new IllegalArgumentException("Unregistered cache: " + name);
        return store;
    }
    public ShutdownReport shutdown(Duration grace) {
        if (Objects.requireNonNull(grace).isNegative()) throw new IllegalArgumentException("Negative grace period");
        synchronized (this) { accepting = false; }
        boolean interrupted = Thread.interrupted();
        try {
            waitActive(Runtime.deadline(grace));
            for (var run : active.values()) run.cancel(new FlowFailure(new ShutdownException("Shutdown deadline exceeded"), FailureKind.SHUTDOWN));
            waitActive(Runtime.deadline(runtime.cancellationBudget.plus(runtime.compensationBudget)));
            var pendingWork = runtime.allWork.stream().filter(work -> !work.finished()).toList();
            var pendingIds = new TreeSet<>(active.keySet());
            var pendingCompensations = new TreeSet<String>();
            for (var work : pendingWork) {
                pendingIds.add(work.run.id);
                if (work.compensation || (work.run.cleanup && !work.run.undo.isEmpty())) pendingCompensations.add(work.run.id);
            }
            var report = new ShutdownReport(new ArrayList<>(pendingIds), pendingWork.size(), new ArrayList<>(pendingCompensations));
            runtime.executor.shutdown();
            return report;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private void waitActive(long deadline) {
        while (!active.isEmpty() && Runtime.remaining(deadline) > 0) {
            try { Thread.sleep(2); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
        }
    }
    @Override public void close() { shutdown(shutdownGrace); }

    public static final class Builder {
        final Map<String, CacheStore> caches = new HashMap<>();
        final Map<String, CircuitBreaker> breakers = new HashMap<>();
        final List<EventListener> listeners = new ArrayList<>();
        LockProvider locks;
        io.orkestra.core.spi.ResourceProvider resources;
        int cacheCapacity = 1024;
        int breakerThreshold = 5;
        Duration breakerInterval = Duration.ofSeconds(30);
        Duration cancellationBudget = Duration.ofSeconds(1);
        Duration compensationBudget = Duration.ofSeconds(5);
        Duration shutdownGrace = Duration.ofSeconds(5);
        boolean strictEvents;
        public Builder cache(String name, CacheStore cache) { caches.put(Policy.name(name), Objects.requireNonNull(cache)); return this; }
        public Builder localCacheCapacity(int capacity) { if (capacity < 1) throw new IllegalArgumentException("Invalid cache capacity"); cacheCapacity = capacity; return this; }
        public Builder resources(io.orkestra.core.spi.ResourceProvider resources) { this.resources = Objects.requireNonNull(resources); return this; }
        public Builder locks(LockProvider locks) { this.locks = Objects.requireNonNull(locks); return this; }
        public Builder breaker(String name, CircuitBreaker breaker) { breakers.put(Policy.name(name), Objects.requireNonNull(breaker)); return this; }
        public Builder breakerDefaults(int threshold, Duration interval) {
            if (threshold < 1) throw new IllegalArgumentException("Invalid breaker threshold");
            breakerThreshold = threshold; breakerInterval = Policy.positive(interval); return this;
        }
        public Builder events(EventListener listener) { listeners.add(Objects.requireNonNull(listener)); return this; }
        public Builder strictEvents(boolean strict) { strictEvents = strict; return this; }
        public Builder cancellationBudget(Duration budget) { cancellationBudget = Policy.positive(budget); return this; }
        public Builder compensationBudget(Duration budget) { compensationBudget = Policy.positive(budget); return this; }
        public Builder shutdownGrace(Duration grace) {
            if (Objects.requireNonNull(grace).isNegative()) throw new IllegalArgumentException("Negative grace"); shutdownGrace = grace; return this;
        }
        public Orchestrator build() { return new Orchestrator(this); }
    }
}
