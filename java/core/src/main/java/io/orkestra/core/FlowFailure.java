package io.orkestra.core;

import io.orkestra.core.exception.ShutdownException;
import io.orkestra.core.execution.FailureKind;
import io.orkestra.core.task.FailureHandler;

/** Internal propagation state. Translation runs after cancellation and compensation. */
final class FlowFailure extends Exception {
    final Exception error;
    final FailureKind kind;
    FailureHandler<Object> handler;
    Object context;
    String timeoutOwner;

    FlowFailure(Exception error) { this(error, kind(error)); }
    FlowFailure(Exception error, FailureKind kind) {
        super(null, error, false, false); this.error = error; this.kind = kind;
    }
    FlowFailure copy() {
        var copy = new FlowFailure(error, kind);
        copy.handler = handler; copy.context = context; copy.timeoutOwner = timeoutOwner;
        return copy;
    }
    FlowFailure timeoutOwner(String path) { timeoutOwner = path; return this; }

    static FailureKind kind(Exception error) {
        if (error instanceof java.util.concurrent.TimeoutException) return FailureKind.TIMEOUT;
        if (error instanceof java.util.concurrent.CancellationException) return FailureKind.CANCELLED;
        if (error instanceof InterruptedException) return FailureKind.CANCELLED;
        if (error instanceof ShutdownException) return FailureKind.SHUTDOWN;
        return FailureKind.TASK_FAILED;
    }
    @SuppressWarnings("unchecked")
    <C> FlowFailure handler(FailureHandler<C> value, C context) {
        if (handler == null && value != null) { handler = (FailureHandler<Object>) value; this.context = context; }
        return this;
    }
}
