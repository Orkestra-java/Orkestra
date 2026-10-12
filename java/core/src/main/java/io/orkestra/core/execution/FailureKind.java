package io.orkestra.core.execution;

/** Failure categories shared with the proposed future execution policies. */
public enum FailureKind {
    TASK_FAILED, TIMEOUT, CANCELLED, SHUTDOWN
}
