package io.orkestra.core.execution;

import java.util.List;

/** IDs include detached work that outlived the synchronous result. */
public record ShutdownReport(List<String> pendingExecutions, int pendingWork, List<String> pendingCompensations) {
    public ShutdownReport(List<String> pendingExecutions, int pendingWork) {
        this(pendingExecutions, pendingWork, List.of());
    }
    public ShutdownReport {
        pendingExecutions = List.copyOf(pendingExecutions);
        pendingCompensations = List.copyOf(pendingCompensations);
    }
    public boolean complete() { return pendingExecutions.isEmpty() && pendingWork == 0 && pendingCompensations.isEmpty(); }
}
