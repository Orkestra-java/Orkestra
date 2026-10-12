package io.orkestra.core.task;

/** Reverses an application effect using the original successful participation. */
@FunctionalInterface
public interface Compensation<C, I, O> {
    void compensate(C context, I input, O output) throws Exception;
}
