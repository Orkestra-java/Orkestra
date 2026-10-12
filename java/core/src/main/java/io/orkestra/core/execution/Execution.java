package io.orkestra.core.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A successful occurrence; duration is measured using a monotonic clock. */
public record Execution<O>(
        String id,
        Instant startedAt,
        Instant completedAt,
        Duration duration,
        O output) {
    public Execution {
        if (Objects.requireNonNull(id, "id").isBlank()) {
            throw new IllegalArgumentException("Execution ID must not be blank");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (Objects.requireNonNull(duration, "duration").isNegative()) {
            throw new IllegalArgumentException("Duration must not be negative");
        }
    }
}
