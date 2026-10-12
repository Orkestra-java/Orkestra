package io.orkestra.core.exception;

import io.orkestra.core.execution.Execution;
import io.orkestra.core.execution.FailureDetails;

/** Fallback only when the original exception cannot carry its diagnostics. */
public final class ExecutionFailureException extends Exception {
    private final FailureDetails details;
    public ExecutionFailureException(Throwable cause, FailureDetails details) {
        super("Execution failed: " + details.executionId(), cause);
        this.details = details;
    }
    public FailureDetails details() { return details; }
}
