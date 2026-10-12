package io.orkestra.core.local.lock;

import io.orkestra.core.spi.LockProvider;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reference-counted entries are removed after both owners and waiters leave. */
public final class LocalLocks implements LockProvider {
    private static final class Entry { final Semaphore semaphore = new Semaphore(1, true); int references; }
    private final Map<Object, Entry> entries = new HashMap<>();
    @Override public Lease acquire(Object key, Duration timeout) throws Exception {
        Objects.requireNonNull(key); Objects.requireNonNull(timeout);
        Entry entry;
        synchronized (entries) { entry = entries.computeIfAbsent(key, ignored -> new Entry()); entry.references++; }
        boolean acquired = false;
        try {
            acquired = entry.semaphore.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) throw new TimeoutException("Timed out waiting for exclusive key");
            return new Lease() {
                private boolean closed;
                @Override public synchronized void close() {
                    if (closed) return; closed = true; entry.semaphore.release(); release(key, entry);
                }
            };
        } finally { if (!acquired) release(key, entry); }
    }
    private void release(Object key, Entry entry) {
        synchronized (entries) { if (--entry.references == 0) entries.remove(key, entry); }
    }
    public int size() { synchronized (entries) { return entries.size(); } }
}
