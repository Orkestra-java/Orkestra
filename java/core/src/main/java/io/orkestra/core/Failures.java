package io.orkestra.core;

import io.orkestra.core.exception.ExecutionFailureException;
import io.orkestra.core.execution.FailureDetails;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Set;

/** Extracts diagnostics without keeping a global registry of exceptions. */
public final class Failures {
    private Failures() {}

    /**
     * Searches the error, causes, and suppressed errors, tolerating cycles.
     * Each occurrence remains identifiable; reused exceptions receive a fresh diagnostic wrapper.
     * Suppression-disabled exceptions use ExecutionFailureException to retain diagnostics.
     */
    public static Optional<FailureDetails> details(Throwable error) {
        if (error == null) {
            return Optional.empty();
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var pending = new ArrayDeque<Throwable>();
        pending.add(error);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof ExecutionFailureException wrapper) {
                return Optional.of(wrapper.details());
            }
            if (current instanceof Diagnostic diagnostic) {
                return Optional.of(diagnostic.details);
            }
            var suppressed = current.getSuppressed();
            for (int i = suppressed.length - 1; i >= 0; i--) {
                if (suppressed[i] instanceof Diagnostic diagnostic) {
                    return Optional.of(diagnostic.details);
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            for (var child : suppressed) {
                pending.addLast(child);
            }
        }
        return Optional.empty();
    }

    static synchronized Exception attach(Exception error, FailureDetails details) {
        for (var previous : error.getSuppressed()) {
            if (previous instanceof Diagnostic) return new ExecutionFailureException(error, details);
        }
        if (error instanceof ExecutionFailureException) return new ExecutionFailureException(error, details);
        var diagnostic = new Diagnostic(details);
        error.addSuppressed(diagnostic);
        for (var suppressed : error.getSuppressed()) if (suppressed == diagnostic) return error;
        return new ExecutionFailureException(error, details);
    }

    private static final class Diagnostic extends Exception {
        private final FailureDetails details;

        private Diagnostic(FailureDetails details) {
            super("Orkestra execution " + details.executionId(), null, false, false);
            this.details = details;
        }
    }
}
