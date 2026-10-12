package io.orkestra.core.internal;

import io.orkestra.core.internal.ExecutionScope;

import java.util.function.Consumer;

public final class Node<C> {
    public final Object identity;
    public final String name;
    public final String kind;
    public final Operation<C> operation;
    public final Policy<C> policy;
    public final boolean compensating;

    public Node(String name, String kind, Operation<C> operation) {
        this(new Object(), name, kind, operation, new Policy<>(), false);
    }
    public Node(Object identity, String name, String kind, Operation<C> operation, Policy<C> policy, boolean compensating) {
        this.identity = identity; this.name = name; this.kind = kind; this.operation = operation;
        if (!kind.equals("TASK") && (policy.cache != null || policy.retry.attempts() > 1)) {
            throw new IllegalArgumentException("Cache and retry policies require a leaf task");
        }
        this.policy = policy; this.compensating = compensating;
    }
    public Node<C> policy(Consumer<Policy.Mutable<C>> change) {
        return new Node<>(identity, name, kind, operation, policy.change(change), compensating);
    }
    public Node<C> named(String name) {
        return new Node<>(identity, Policy.name(name), kind, operation, policy, compensating);
    }
    @FunctionalInterface
    public interface Operation<C> {
        Object execute(C context, Object input, ExecutionScope frame) throws Exception;
    }
}
