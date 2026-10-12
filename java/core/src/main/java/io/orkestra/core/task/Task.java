package io.orkestra.core.task;

/** An application operation with explicit context, input, and output types. */
@FunctionalInterface
public interface Task<C, I, O> {
    O execute(C context, I input) throws Exception;
}
