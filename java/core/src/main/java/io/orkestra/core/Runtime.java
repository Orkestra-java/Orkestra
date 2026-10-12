package io.orkestra.core;

import io.orkestra.core.exception.CircuitOpenException;
import io.orkestra.core.exception.ShutdownException;
import io.orkestra.core.execution.Execution;
import io.orkestra.core.execution.ExecutionOptions;
import io.orkestra.core.execution.FailureKind;
import io.orkestra.core.internal.ExecutionScope;
import io.orkestra.core.internal.Node;
import io.orkestra.core.internal.Policy;
import io.orkestra.core.policy.CachePolicy;
import io.orkestra.core.spi.CacheStore;
import io.orkestra.core.spi.CircuitBreaker;
import io.orkestra.core.spi.EventListener;
import io.orkestra.core.spi.ExecutionEvent;
import io.orkestra.core.spi.HistoryStore;
import io.orkestra.core.spi.LockProvider;
import io.orkestra.core.spi.ResourceProvider;
import io.orkestra.core.task.Compensation;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Execution machinery, separate from immutable definitions and public extension contracts. */
final class Runtime {
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final Set<Work<?>> allWork = ConcurrentHashMap.newKeySet();
    private record Capacity(int limit, Semaphore semaphore) {}
    final Map<Object, Capacity> capacities = Collections.synchronizedMap(new WeakHashMap<>());
    final Map<String, Integer> isolationLimits = new ConcurrentHashMap<>();
    final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    final Map<String, CacheStore> namedCaches;
    final Map<Object, CacheStore> localCaches = Collections.synchronizedMap(new WeakHashMap<>());
    final ConcurrentHashMap<CacheKey, Flight> flights = new ConcurrentHashMap<>();
    final LockProvider locks;
    final io.orkestra.core.spi.ResourceProvider resources;
    final List<EventListener> listeners;
    final java.util.concurrent.atomic.AtomicLong eventFailures = new java.util.concurrent.atomic.AtomicLong();
    final boolean strictEvents;
    final int cacheCapacity;
    final int breakerThreshold;
    final Duration breakerInterval;
    final Duration cancellationBudget;
    final Duration compensationBudget;

    Runtime(Orchestrator.Builder options) {
        resources = options.resources == null ? new io.orkestra.core.local.resources.LocalResources() : options.resources;
        namedCaches = Map.copyOf(options.caches);
        locks = options.locks == null ? Objects.requireNonNull(resources.locks()) : options.locks; listeners = List.copyOf(options.listeners);
        strictEvents = options.strictEvents; cacheCapacity = options.cacheCapacity;
        breakerThreshold = options.breakerThreshold; breakerInterval = options.breakerInterval;
        breakers.putAll(options.breakers); cancellationBudget = options.cancellationBudget;
        compensationBudget = options.compensationBudget;
    }

    static long nanos(Duration duration) {
        try { return Math.min(duration.toNanos(), Long.MAX_VALUE / 4); }
        catch (ArithmeticException ignored) { return Long.MAX_VALUE / 4; }
    }
    static long deadline(Duration budget) { return System.nanoTime() + nanos(budget); }
    static long remaining(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, deadline - System.nanoTime());
    }

    final class Run {
        final String id;
        final ExecutionOptions options;
        final Set<Work<?>> work = ConcurrentHashMap.newKeySet();
        final List<Undo> undo = Collections.synchronizedList(new ArrayList<>());
        volatile FlowFailure stop;
        volatile boolean cleanup;
        volatile boolean cleanupInterrupted;
        Run(String id, ExecutionOptions options) { this.id = id; this.options = options; }
        synchronized void cancel(FlowFailure reason) {
            if (stop == null) stop = reason;
            for (var item : work) if (!item.finished()) item.cancel();
        }
        void emit(Frame frame, String type, String name, Throwable error) {
            var event = new ExecutionEvent(id, options.correlationId(), frame.span, frame.parent,
                    name, frame.kind, type, Instant.now(), Duration.ofNanos(System.nanoTime() - frame.started),
                    error, options.attributes());
            for (var listener : listeners) {
                try { listener.onEvent(event); }
                catch (RuntimeException failure) {
                    eventFailures.incrementAndGet();
                    for (var candidate : listeners) {
                        if (candidate instanceof HistoryStore history) {
                            try { history.markIncomplete(id); } catch (RuntimeException ignored) { eventFailures.incrementAndGet(); }
                        }
                    }
                    if (strictEvents && !cleanup) throw failure;
                }
            }
        }
    }

    static final class Frame implements io.orkestra.core.internal.ExecutionScope {
        final Runtime runtime;
        final Run run;
        final long deadline;
        final String span;
        final String parent;
        final String kind;
        final String path;
        final String deadlineOwner;
        final boolean compensating;
        final boolean cleanup;
        Frame ancestor;
        volatile Object heldCapacity;
        volatile Object heldExclusive;
        final long started = System.nanoTime();
        Frame(Runtime runtime, Run run, long deadline, String span, String parent,
                String kind, String path, boolean compensating, boolean cleanup) {
            this(runtime, run, deadline, span, parent, kind, path, compensating, cleanup, path);
        }
        Frame(Runtime runtime, Run run, long deadline, String span, String parent,
                String kind, String path, boolean compensating, boolean cleanup, String deadlineOwner) {
            this.deadlineOwner = deadlineOwner;
            this.runtime = runtime; this.run = run; this.deadline = deadline; this.span = span;
            this.parent = parent; this.kind = kind; this.path = path; this.compensating = compensating; this.cleanup = cleanup;
        }
        Frame child(Node<?> node) {
            long own = node.policy.timeout == null ? Long.MAX_VALUE : Runtime.deadline(node.policy.timeout);
            String id = UUID.randomUUID().toString();
            var child = new Frame(runtime, run, Math.min(deadline, own), id, span, node.kind, path + "/" + id,
                    compensating || node.compensating, cleanup, own < deadline ? path + "/" + id : deadlineOwner);
            child.ancestor = this;
            return child;
        }
        void rejectAncestorResource(Object key, boolean exclusive) {
            for (Frame owner = ancestor; owner != null; owner = owner.ancestor) {
                Object held = exclusive ? owner.heldExclusive : owner.heldCapacity;
                if (held != null && Objects.equals(held, key)) {
                    throw new IllegalArgumentException("Nested " + (exclusive ? "exclusive key" : "capacity/isolation")
                            + " reacquires a resource held by ancestor " + owner.kind
                            + "; configure the resource in only one scope");
                }
            }
        }
        @Override public void check() throws Exception {
            if (!cleanup && run.stop != null) throw new FlowFailure(run.stop.error, run.stop.kind).timeoutOwner(run.stop.timeoutOwner);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Execution interrupted");
            if (remaining(deadline) == 0) throw new FlowFailure(new TimeoutException("Execution deadline exceeded")).timeoutOwner(deadlineOwner);
        }
        @Override public void event(String type, String name, Throwable error) { run.emit(this, type, name, error); }
        @Override public void pause(Duration duration) throws Exception {
            long until = Runtime.deadline(duration);
            while (remaining(until) > 0) {
                check();
                TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(20), Math.min(remaining(until), remaining(deadline))));
            }
            check();
        }
        @Override public <C> Object execute(Node<C> node, C context, Object input) throws Exception {
            return runtime.execute(node, context, input, this);
        }
        @Override public List<Object> parallel(List<Callable<Object>> jobs, int limit) throws Exception {
            return runtime.parallel(jobs, this, limit);
        }
        void acquire(Semaphore semaphore) throws Exception {
            event("CAPACITY_WAIT", "capacity", null);
            while (true) {
                check();
                if (semaphore.tryAcquire(Math.min(remaining(deadline), TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)) return;
            }
        }
    }

    final class Work<V> implements Runnable {
        final FutureTask<V> future;
        final CountDownLatch done = new CountDownLatch(1);
        final Run run;
        final boolean compensation;
        Work(Callable<V> task, Run run, Consumer<Work<V>> completion, boolean compensation) {
            this.run = run; this.compensation = compensation;
            future = new FutureTask<>(task) {
                @Override protected void done() { if (completion != null) completion.accept(Work.this); }
            };
        }
        @Override public void run() {
            try { future.run(); }
            finally { done.countDown(); allWork.remove(this); run.work.remove(this); }
        }
        boolean finished() { return done.getCount() == 0; }
        void cancel() { future.cancel(true); }
    }

    <V> Work<V> work(Callable<V> task, Run run, Consumer<Work<V>> completion) {
        return work(task, run, completion, false);
    }
    private <V> Work<V> work(Callable<V> task, Run run, Consumer<Work<V>> completion, boolean compensation) {
        var work = new Work<>(task, run, completion, compensation); allWork.add(work); run.work.add(work);
        try { executor.execute(work); }
        catch (RejectedExecutionException error) { allWork.remove(work); run.work.remove(work); work.done.countDown(); throw error; }
        return work;
    }
    <V> V resolve(Work<V> work) throws Exception {
        try { return work.future.get(); }
        catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof Exception exception) throw exception;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw new IllegalStateException(wrapped.getCause());
        }
    }
    <V> V await(Work<V> work, Frame frame) throws Exception {
        while (true) {
            frame.check();
            try {
                return work.future.get(Math.min(remaining(frame.deadline), TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException tick) { frame.check(); }
            catch (ExecutionException wrapped) { return resolve(work); }
            catch (CancellationException cancelled) {
                if (frame.run.stop != null) throw frame.run.stop;
                throw cancelled;
            }
        }
    }

    <C> Object execute(Node<C> node, C context, Object input, Frame parent) throws Exception {
        var frame = parent.child(node);
        try {
            frame.check(); frame.event("STARTED", node.name, null);
            Object output;
            if (node.policy.cache != null) output = cached(node, context, input, frame);
            else if (frame.deadline != Long.MAX_VALUE) {
                var work = work(() -> attempts(node, context, input, frame), frame.run, null);
                try { output = await(work, frame); }
                catch (Exception | Error error) { work.cancel(); throw error; }
            } else output = attempts(node, context, input, frame);
            frame.check();
            frame.event("SUCCEEDED", node.name, null);
            return output;
        } catch (Exception error) {
            var failure = error instanceof FlowFailure flow ? flow : new FlowFailure(error);
            if (error instanceof TimeoutException && remaining(frame.deadline) == 0) failure.timeoutOwner(frame.deadlineOwner);
            if ((error instanceof InterruptedException || error instanceof CancellationException)
                    && frame.run.stop != null && frame.run.stop.kind == FailureKind.TIMEOUT) {
                failure = new FlowFailure(frame.run.stop.error, FailureKind.TIMEOUT).timeoutOwner(frame.run.stop.timeoutOwner);
            }
            if (failure.timeoutOwner == null || !frame.path.startsWith(failure.timeoutOwner + "/")) {
                failure.handler(node.policy.handler, context);
            }
            frame.event("FAILED", node.name, failure.error);
            throw failure;
        } catch (Error fatal) { frame.event("FAILED", node.name, fatal); throw fatal; }
    }

    private <C> Object attempts(Node<C> node, C context, Object input, Frame frame) throws Exception {
        Object exclusiveKey = node.policy.exclusive == null ? null
                : Objects.requireNonNull(node.policy.exclusive.apply(context), "Exclusive key");
        return attempts(node, context, input, frame, exclusiveKey);
    }

    private <C> Object attempts(Node<C> node, C context, Object input, Frame frame, Object exclusiveKey) throws Exception {
        Policy<C> policy = node.policy;
        Semaphore capacity = null;
        boolean acquired = false;
        LockProvider.Lease lease = null;
        try {
            if (policy.capacity != Integer.MAX_VALUE || policy.isolation != null) {
                Object key = node.identity;
                if (policy.isolation != null) {
                    Integer existing = isolationLimits.putIfAbsent(policy.isolation, policy.capacity);
                    if (existing != null && existing != policy.capacity) throw new IllegalArgumentException("Conflicting capacity for isolation " + policy.isolation);
                    key = policy.isolation;
                }
                var configured = capacities.computeIfAbsent(key, ignored -> new Capacity(policy.capacity, new Semaphore(policy.capacity, true)));
                if (configured.limit != policy.capacity) throw new IllegalArgumentException("Conflicting capacity for the same participation");
                capacity = configured.semaphore;
                frame.rejectAncestorResource(key, false);
                frame.acquire(capacity); acquired = true; frame.heldCapacity = key;
            }
            if (policy.exclusive != null) {
                frame.event("EXCLUSIVE_WAIT", node.name, null);
                frame.rejectAncestorResource(exclusiveKey, true);
                lease = locks.acquire(exclusiveKey,
                        Duration.ofNanos(remaining(frame.deadline)));
                frame.heldExclusive = exclusiveKey;
            }
            CircuitBreaker breaker = policy.guard == null ? null : breakers.computeIfAbsent(policy.guard,
                    name -> resources.breaker(name, breakerThreshold, breakerInterval));
            for (int attempt = 1; ; attempt++) {
                frame.check(); frame.event("ATTEMPT", node.name + "#" + attempt, null);
                CircuitBreaker.Permit permit = breaker == null ? null : breaker.acquire();
                try {
                    Object output = node.operation.execute(context, input, frame);
                    if (frame.compensating && node.policy.compensation != null) {
                        synchronized (frame.run.undo) {
                            frame.run.undo.removeIf(previous -> previous.path.startsWith(frame.path + "/"));
                            frame.run.undo.add(new Undo(frame.path, node.name,
                                    () -> node.policy.compensation.compensate(context, input, output), frame));
                        }
                    }
                    frame.check();
                    if (permit != null) permit.success();
                    return output;
                } catch (Exception error) {
                    if (permit != null) permit.failure();
                    Exception cause = error instanceof FlowFailure flow ? flow.error : error;
                    if (error instanceof FlowFailure || cause instanceof InterruptedException
                            || cause instanceof TimeoutException || cause instanceof CancellationException
                            || cause instanceof CircuitOpenException || cause instanceof ShutdownException
                            || attempt >= policy.retry.attempts() || !policy.retry.retryIf().test(cause)) throw error;
                    frame.event("RETRY", node.name, error); frame.pause(policy.retry.delay());
                } catch (Error fatal) { if (permit != null) permit.failure(); throw fatal; }
            }
        } finally {
            try { if (lease != null) lease.close(); }
            finally {
                frame.heldExclusive = null;
                if (acquired) capacity.release();
                frame.heldCapacity = null;
            }
        }
    }

    private record Indexed(int index, Object output) {}
    List<Object> parallel(List<Callable<Object>> jobs, Frame frame, int limit) throws Exception {
        var complete = new LinkedBlockingQueue<Work<Indexed>>();
        var pending = new HashSet<Work<Indexed>>();
        var outputs = new ArrayList<Object>(Collections.nCopies(jobs.size(), null));
        int next = 0;
        try {
            // Submit only the active window: a concurrency limit also bounds waiting virtual threads.
            while (next < jobs.size() && pending.size() < limit) {
                frame.check();
                pending.add(participation(next++, jobs, frame, complete));
            }
            while (!pending.isEmpty()) {
                frame.check();
                var done = complete.poll(Math.min(remaining(frame.deadline), TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS);
                if (done == null) continue;
                pending.remove(done);
                var result = resolve(done);
                outputs.set(result.index, result.output);
                if (next < jobs.size()) {
                    frame.check(); frame.event("CAPACITY_WAIT", "group", null);
                    pending.add(participation(next++, jobs, frame, complete));
                }
            }
            return outputs;
        } catch (Exception error) {
            var failure = error instanceof FlowFailure flow ? flow : new FlowFailure(error);
            frame.run.cancel(failure); throw failure;
        } finally { for (var item : pending) if (!item.finished()) item.cancel(); }
    }
    private Work<Indexed> participation(int index, List<Callable<Object>> jobs, Frame frame,
            LinkedBlockingQueue<Work<Indexed>> completion) {
        return work(() -> { frame.check(); return new Indexed(index, jobs.get(index).call()); }, frame.run, completion::add);
    }

    private record CacheKey(CacheStore store, Object participation, Object key) {}
    private final class Flight {
        final AtomicInteger interested = new AtomicInteger();
        Work<Object> work;
    }
    private <C> Object cached(Node<C> node, C context, Object input, Frame frame) throws Exception {
        CachePolicy<C> policy = node.policy.cache;
        CacheStore store = policy.name() == null ? localCaches.computeIfAbsent(node.identity, ignored -> resources.cache(cacheCapacity))
                : namedCaches.get(policy.name());
        if (store == null) throw new IllegalArgumentException("Unregistered named cache: " + policy.name());
        Object key = Objects.requireNonNull(policy.key().apply(context), "Cache key");
        var hit = store.get(key);
        if (hit.isPresent()) { frame.event("CACHE_HIT", node.name, null); return hit.get().output(); }
        if (node.policy.capacity != Integer.MAX_VALUE || node.policy.isolation != null) {
            frame.rejectAncestorResource(node.policy.isolation == null ? node.identity : node.policy.isolation, false);
        }
        Object exclusiveKey = node.policy.exclusive == null ? null
                : Objects.requireNonNull(node.policy.exclusive.apply(context), "Exclusive key");
        if (exclusiveKey != null) frame.rejectAncestorResource(exclusiveKey, true);
        var flightKey = new CacheKey(store, node.identity, key);
        Flight flight = flights.compute(flightKey, (ignored, current) -> {
            if (current != null && !current.work.future.isCancelled()) { current.interested.incrementAndGet(); return current; }
            var created = new Flight(); created.interested.incrementAndGet();
            var detachedRun = new Run(frame.run.id, frame.run.options);
            long loaderDeadline = node.policy.timeout == null ? Long.MAX_VALUE : deadline(node.policy.timeout);
            var detached = new Frame(this, detachedRun, loaderDeadline, frame.span, frame.parent, frame.kind, frame.path, false, false);
            detached.ancestor = frame.ancestor;
            created.work = work(() -> {
                Object output = attempts(node, context, input, detached, exclusiveKey);
                detached.check(); store.put(key, output, policy.ttl()); return output;
            }, detachedRun, null);
            return created;
        });
        frame.event("CACHE_WAIT", node.name, null);
        try { return await(flight.work, frame); }
        catch (FlowFailure shared) { throw shared.copy(); }
        finally {
            flights.computeIfPresent(flightKey, (ignored, current) -> {
                if (current != flight) return current;
                if (flight.interested.decrementAndGet() != 0) return current;
                if (!flight.work.finished()) {
                    frame.run.work.add(flight.work);
                    flight.work.run.cancel(new FlowFailure(new CancellationException("Cache load has no remaining subscribers")));
                }
                return null;
            });
        }
    }

    private record Undo(String path, String name, UndoAction action, Frame original) {}
    @FunctionalInterface private interface UndoAction { void execute() throws Exception; }
    boolean quiesce(Run run, Duration budget) {
        long until = deadline(budget);
        while (run.work.stream().anyMatch(work -> !work.finished()) && remaining(until) > 0) {
            try { TimeUnit.MILLISECONDS.sleep(2); }
            catch (InterruptedException interrupted) { run.cleanupInterrupted = true; Thread.interrupted(); }
        }
        return run.work.stream().noneMatch(work -> !work.finished());
    }
    List<Throwable> compensate(Run run) {
        var errors = new ArrayList<Throwable>();
        var records = new ArrayList<Undo>();
        synchronized (run.undo) { records.addAll(run.undo); run.undo.clear(); }
        Collections.reverse(records);
        long until = deadline(compensationBudget);
        for (var record : records) {
            if (remaining(until) == 0) { errors.add(new TimeoutException("Compensation budget exhausted")); break; }
            var frame = new Frame(this, run, until, UUID.randomUUID().toString(), record.original.span,
                    "COMPENSATION", record.path + "/undo", false, true);
            frame.event("COMPENSATION_STARTED", record.name, null);
            var work = work(() -> { record.action.execute(); return null; }, run, null, true);
            try { await(work, frame); frame.event("COMPENSATION_SUCCEEDED", record.name, null); }
            catch (Exception error) {
                work.cancel();
                Exception cause = error instanceof FlowFailure flow ? flow.error : error;
                errors.add(cause);
                if (cause instanceof InterruptedException) { run.cleanupInterrupted = true; Thread.interrupted(); }
                frame.event("COMPENSATION_FAILED", record.name, cause);
            }
            catch (Error error) { work.cancel(); errors.add(error); frame.event("COMPENSATION_FAILED", record.name, error); }
        }
        return errors;
    }
}
