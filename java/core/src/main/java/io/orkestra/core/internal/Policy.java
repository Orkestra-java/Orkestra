package io.orkestra.core.internal;

import io.orkestra.core.policy.CachePolicy;
import io.orkestra.core.policy.RetryPolicy;
import io.orkestra.core.task.Compensation;
import io.orkestra.core.task.FailureHandler;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

public final class Policy<C> {
    public final Duration timeout;
    public final int capacity;
    public final String isolation;
    public final Function<C, ?> exclusive;
    public final RetryPolicy retry;
    public final String guard;
    public final CachePolicy<C> cache;
    public final Compensation<C, Object, Object> compensation;
    public final FailureHandler<C> handler;

    public Policy() { this(new Mutable<>()); }
    private Policy(Mutable<C> value) {
        timeout = value.timeout; capacity = value.capacity; isolation = value.isolation;
        exclusive = value.exclusive; retry = value.retry; guard = value.guard;
        cache = value.cache; compensation = value.compensation; handler = value.handler;
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        if (timeout != null) positive(timeout);
        if (isolation != null) name(isolation);
        if (guard != null) name(guard);
        if (cache != null && compensation != null) {
            throw new IllegalArgumentException("Shared cache and compensation cannot coexist on one participation");
        }
    }
    public Policy<C> change(Consumer<Mutable<C>> change) {
        var value = new Mutable<C>();
        value.timeout = timeout; value.capacity = capacity; value.isolation = isolation;
        value.exclusive = exclusive; value.retry = retry; value.guard = guard;
        value.cache = cache; value.compensation = compensation; value.handler = handler;
        change.accept(value);
        return new Policy<>(value);
    }
    public static Duration positive(Duration duration) {
        if (Objects.requireNonNull(duration).isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Duration must be positive");
        }
        return duration;
    }
    public static String name(String name) {
        if (Objects.requireNonNull(name).isBlank()) throw new IllegalArgumentException("Name must not be blank");
        return name;
    }
    public static final class Mutable<C> {
        public Duration timeout;
        public int capacity = Integer.MAX_VALUE;
        public String isolation;
        public Function<C, ?> exclusive;
        public RetryPolicy retry = new RetryPolicy(1, Duration.ZERO, error -> false);
        public String guard;
        public CachePolicy<C> cache;
        public Compensation<C, Object, Object> compensation;
        public FailureHandler<C> handler;
    }
}
