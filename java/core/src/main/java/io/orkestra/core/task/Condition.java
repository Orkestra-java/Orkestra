package io.orkestra.core.task;

@FunctionalInterface
public interface Condition<C, I> {
    boolean test(C context, I input) throws Exception;
}
