package io.orkestra.core.spi;

/** A permit belongs to one attempt, including its half-open outcome. */
public interface CircuitBreaker {
    interface Permit {
        void success();
        void failure();
    }
    Permit acquire() throws Exception;
}
