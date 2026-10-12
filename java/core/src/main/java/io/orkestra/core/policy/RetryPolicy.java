package io.orkestra.core.policy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

public record RetryPolicy(int attempts, Duration delay, Predicate<Throwable> retryIf) {
    public RetryPolicy {
        if (attempts < 1) throw new IllegalArgumentException("Attempts must be positive");
        if (Objects.requireNonNull(delay).isNegative()) throw new IllegalArgumentException("Negative delay");
        Objects.requireNonNull(retryIf);
    }

    public static final class Builder {
        private int attempts = 1;
        private Duration delay = Duration.ZERO;
        private Predicate<Throwable> predicate = error -> false;
        public Builder upTo(int attempts) { this.attempts = attempts; return this; }
        public Builder waiting(Duration delay) { this.delay = delay; return this; }
        public Builder retryIf(Predicate<Throwable> predicate) { this.predicate = predicate; return this; }
        public RetryPolicy build() { return new RetryPolicy(attempts, delay, predicate); }
    }
}
