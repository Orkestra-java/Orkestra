package io.orkestra.core.spi;

import java.time.Duration;
import java.util.Optional;

/** Thread-safe extension contract. An Entry can carry a null successful output. */
public interface CacheStore {
    record Entry(Object output) {}
    Optional<Entry> get(Object key);
    void put(Object key, Object output, Duration ttl);
    void invalidate(Object key);
    void invalidateAll();
}
