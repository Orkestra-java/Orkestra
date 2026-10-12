package io.orkestra.core.spi;

import java.time.Duration;

/** Factories for coordinator-owned resources, created by default or supplied explicitly. */
public interface ResourceProvider {
    LockProvider locks();
    CacheStore cache(int capacity);
    CircuitBreaker breaker(String name, int threshold, Duration openInterval);
}
