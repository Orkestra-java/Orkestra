package io.orkestra.core.composition;

@FunctionalInterface
public interface Join<C, O> {
    O combine(C context, Results results) throws Exception;
}
