package io.orkestra.core.policy;

import io.orkestra.core.internal.Policy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

public record CachePolicy<C>(String name, Function<C, ?> key, Duration ttl) {
    public CachePolicy {
        if (name != null && name.isBlank()) throw new IllegalArgumentException("Blank cache name");
        Objects.requireNonNull(key, "Cache key function");
        Policy.positive(ttl);
    }

    public static final class Builder<C> {
        private String name;
        private Function<C, ?> key;
        private Duration ttl = Duration.ofMinutes(5);
        public Builder<C> named(String name) { this.name = Objects.requireNonNull(name); return this; }
        public Builder<C> key(Function<C, ?> key) { this.key = key; return this; }
        public Builder<C> expiresAfter(Duration ttl) { this.ttl = ttl; return this; }
        public CachePolicy<C> build() { return new CachePolicy<>(name, key, ttl); }
    }
}
