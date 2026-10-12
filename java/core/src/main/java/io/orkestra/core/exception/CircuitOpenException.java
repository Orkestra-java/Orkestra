package io.orkestra.core.exception;

public final class CircuitOpenException extends Exception {
    public CircuitOpenException(String name) { super("Circuit is open: " + name); }
}
