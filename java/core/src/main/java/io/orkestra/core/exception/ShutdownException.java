package io.orkestra.core.exception;

public final class ShutdownException extends IllegalStateException {
    public ShutdownException(String message) { super(message); }
}
