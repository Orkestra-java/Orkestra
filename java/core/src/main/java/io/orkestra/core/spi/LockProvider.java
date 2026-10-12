package io.orkestra.core.spi;

import java.time.Duration;

/** Optional distributed implementations must respect timeout and interruption. */
public interface LockProvider {
    interface Lease extends AutoCloseable { @Override void close(); }
    Lease acquire(Object key, Duration timeout) throws Exception;
}
