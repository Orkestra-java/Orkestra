package io.orkestra.core.local.resources;

import io.orkestra.core.spi.CacheStore;
import io.orkestra.core.spi.CircuitBreaker;
import io.orkestra.core.spi.LockProvider;
import io.orkestra.core.spi.ResourceProvider;
import io.orkestra.core.local.cache.MemoryCache;
import io.orkestra.core.local.lock.LocalLocks;
import io.orkestra.core.local.resilience.LocalCircuitBreaker;

import java.time.Duration;

/** Local, coordinator-owned defaults. Each factory call creates independent resource state. */
public final class LocalResources implements ResourceProvider {
    @Override public LockProvider locks() { return new LocalLocks(); }
    @Override public CacheStore cache(int capacity) { return new MemoryCache(capacity); }
    @Override public CircuitBreaker breaker(String name, int threshold, Duration interval) {
        return new LocalCircuitBreaker(name, threshold, interval);
    }
}
