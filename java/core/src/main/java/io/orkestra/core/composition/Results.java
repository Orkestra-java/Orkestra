package io.orkestra.core.composition;

import java.util.IdentityHashMap;
import java.util.Map;

/** Successful outputs indexed by participation identity, never by display name. */
public final class Results {
    private final Map<TaskRef<?, ?, ?>, Object> outputs;

    Results(Map<TaskRef<?, ?, ?>, Object> outputs) {
        this.outputs = new IdentityHashMap<>(outputs);
    }

    @SuppressWarnings("unchecked")
    public <C, I, O> O get(TaskRef<C, I, O> reference) {
        if (!outputs.containsKey(reference)) {
            throw new IllegalArgumentException("Reference does not belong to this completed group");
        }
        return (O) outputs.get(reference);
    }
}
