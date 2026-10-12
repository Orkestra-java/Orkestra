package io.orkestra.core.execution;

import java.util.Objects;
import java.util.List;

/** Diagnostics for a failed occurrence, including cleanup errors and unfinished work. */
public record FailureDetails(
        String executionId,
        FailureKind kind,
        boolean compensationFailed,
        boolean workPending,
        List<Throwable> compensationErrors) {
    public FailureDetails(String executionId, FailureKind kind, boolean compensationFailed, boolean workPending) {
        this(executionId, kind, compensationFailed, workPending, List.of());
    }
    public FailureDetails {
        if (Objects.requireNonNull(executionId, "executionId").isBlank()) {
            throw new IllegalArgumentException("Execution ID must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        compensationErrors = List.copyOf(compensationErrors);
    }
}
