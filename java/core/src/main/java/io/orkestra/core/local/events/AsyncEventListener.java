package io.orkestra.core.local.events;

import io.orkestra.core.spi.EventListener;
import io.orkestra.core.spi.ExecutionEvent;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded asynchronous export. The delegate is application-owned and must honor interruption. */
public final class AsyncEventListener implements EventListener, AutoCloseable {
    public enum OverflowPolicy { DROP, FAIL }
    private final EventListener delegate;
    private final ArrayBlockingQueue<ExecutionEvent> queue;
    private final OverflowPolicy policy;
    private final Consumer<String> incomplete;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean closing;
    private volatile ExecutionEvent exporting;
    private final Thread worker;
    public AsyncEventListener(EventListener delegate, int capacity, OverflowPolicy policy, Consumer<String> incomplete) {
        this.delegate = Objects.requireNonNull(delegate); this.queue = new ArrayBlockingQueue<>(capacity);
        this.policy = Objects.requireNonNull(policy); this.incomplete = Objects.requireNonNull(incomplete);
        worker = Thread.ofVirtual().name("orkestra-event-export").start(this::drain);
    }
    @Override public synchronized void onEvent(ExecutionEvent event) {
        Objects.requireNonNull(event);
        if (closing || !queue.offer(event)) {
            dropped.incrementAndGet(); markIncomplete(event.executionId());
            if (policy == OverflowPolicy.FAIL) throw new IllegalStateException("Event export queue is full or closed");
        }
    }
    private void drain() {
        try {
            while (!closing || !queue.isEmpty()) {
                var event = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (event == null) continue;
                exporting = event;
                try { delegate.onEvent(event); }
                catch (RuntimeException error) { failed.incrementAndGet(); markIncomplete(event.executionId()); }
                finally { exporting = null; }
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        finally {
            ExecutionEvent event;
            while ((event = queue.poll()) != null) { dropped.incrementAndGet(); markIncomplete(event.executionId()); }
        }
    }
    private void markIncomplete(String id) {
        try { incomplete.accept(id); } catch (RuntimeException ignored) { failed.incrementAndGet(); }
    }
    public long dropped() { return dropped.get(); }
    public long failures() { return failed.get(); }
    public int pending() { return queue.size() + (exporting == null ? 0 : 1); }
    public boolean close(Duration budget) {
        if (budget.isNegative()) throw new IllegalArgumentException("Negative budget");
        synchronized (this) { closing = true; }
        try { worker.join(budget); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) {
            worker.interrupt();
            var active = exporting;
            if (active != null) markIncomplete(active.executionId());
            ExecutionEvent queued;
            while ((queued = queue.poll()) != null) { dropped.incrementAndGet(); markIncomplete(queued.executionId()); }
        }
        return !worker.isAlive();
    }
    @Override public void close() { close(Duration.ofSeconds(5)); }
}
