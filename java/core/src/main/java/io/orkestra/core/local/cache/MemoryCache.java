package io.orkestra.core.local.cache;

import io.orkestra.core.spi.CacheStore;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Bounded LRU cache, with TTL measured by the monotonic clock. */
public final class MemoryCache implements CacheStore {
    private record Value(Object output, long created, long ttl) {}
    private final int capacity;
    private final LinkedHashMap<Object, Value> values = new LinkedHashMap<>(16, 0.75f, true);
    public MemoryCache() { this(1024); }
    public MemoryCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
    }
    @Override public synchronized Optional<Entry> get(Object key) {
        var value = values.get(key);
        if (value == null) return Optional.empty();
        if (System.nanoTime() - value.created >= value.ttl) { values.remove(key); return Optional.empty(); }
        return Optional.of(new Entry(value.output));
    }
    @Override public synchronized void put(Object key, Object output, Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("TTL must be positive");
        long nanos;
        try { nanos = ttl.toNanos(); } catch (ArithmeticException large) { nanos = Long.MAX_VALUE; }
        values.put(key, new Value(output, System.nanoTime(), nanos));
        while (values.size() > capacity) values.remove(values.keySet().iterator().next());
    }
    @Override public synchronized void invalidate(Object key) { values.remove(key); }
    @Override public synchronized void invalidateAll() { values.clear(); }
    public synchronized int size() { return values.size(); }
}
