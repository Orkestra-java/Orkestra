package io.orkestra.core.exception;

import io.orkestra.core.composition.Composition;

public final class RequirementException extends Exception {
    public RequirementException() { super("Composition requirement was rejected"); }
}
