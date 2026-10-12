package io.orkestra.core.local.resilience;

import io.orkestra.core.exception.CircuitOpenException;
import io.orkestra.core.spi.CircuitBreaker;

import java.time.Duration;

/** Consecutive-failure breaker with one half-open probe. */
public final class LocalCircuitBreaker implements CircuitBreaker {
    private final String name;
    private final int threshold;
    private final long openNanos;
    private int failures;
    private long openedAt;
    private boolean open;
    private boolean probe;
    public LocalCircuitBreaker(String name, int threshold, Duration interval) {
        if (threshold < 1 || interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("Invalid breaker settings");
        this.name = name; this.threshold = threshold; this.openNanos = interval.toNanos();
    }
    @Override public synchronized Permit acquire() throws CircuitOpenException {
        boolean halfOpen = open;
        if (open) {
            if (probe || System.nanoTime() - openedAt < openNanos) throw new CircuitOpenException(name);
            probe = true;
        }
        return new Permit() {
            private boolean completed;
            @Override public void success() { complete(true); }
            @Override public void failure() { complete(false); }
            private void complete(boolean success) {
                synchronized (LocalCircuitBreaker.this) {
                    if (completed) return; completed = true;
                    if (halfOpen) {
                        probe = false;
                        if (success) { open = false; failures = 0; }
                        else { open = true; openedAt = System.nanoTime(); }
                    } else if (!open) {
                        if (success) failures = 0;
                        else if (++failures >= threshold) { open = true; openedAt = System.nanoTime(); }
                    }
                }
            }
        };
    }
}
