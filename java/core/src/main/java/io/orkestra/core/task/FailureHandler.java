package io.orkestra.core.task;

/** Translates a failure into an application exception; never resumes execution. */
@FunctionalInterface
public interface FailureHandler<C> {
    Exception translate(C context, Throwable cause) throws Exception;
}
