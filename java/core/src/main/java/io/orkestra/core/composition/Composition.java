package io.orkestra.core.composition;

import io.orkestra.core.internal.ExecutionScope;
import io.orkestra.core.internal.Node;
import io.orkestra.core.internal.Policy;
import io.orkestra.core.task.FailureHandler;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** An immutable definition, independently reusable across executions. */
public final class Composition<C, I, O> {
    final List<Node<C>> steps;
    final String name;
    final Policy<C> policy;
    final int groupLimit;

    Composition(List<Node<C>> steps, String name, Policy<C> policy, int groupLimit) {
        this.steps = List.copyOf(steps); this.name = name; this.policy = policy; this.groupLimit = groupLimit;
    }
    public Optional<String> name() { return Optional.ofNullable(name); }
    public Composition<C, I, O> onFailure(FailureHandler<C> handler) {
        return new Composition<>(steps, name, policy.change(p -> p.handler = Objects.requireNonNull(handler)), groupLimit);
    }
    public Composition<C, I, O> within(Duration timeout) {
        return new Composition<>(steps, name, policy.change(p -> p.timeout = Policy.positive(timeout)), groupLimit);
    }
    public Node<C> definition() {
        return new Node<>(this, name == null ? "composition" : name, "COMPOSITION",
                (context, input, frame) -> run(context, input, frame), policy, false);
    }
    Object run(C context, Object input, ExecutionScope frame) throws Exception {
        Object current = input;
        frame.check();
        for (var step : steps) {
            frame.check();
            current = frame.execute(step, context, current);
        }
        frame.check();
        return current;
    }
}
