package io.orkestra.core.composition;

import io.orkestra.core.internal.Node;
import io.orkestra.core.internal.Policy;
import io.orkestra.core.policy.CachePolicy;
import io.orkestra.core.policy.RetryPolicy;
import io.orkestra.core.task.Compensation;
import io.orkestra.core.task.FailureHandler;
import io.orkestra.core.task.Task;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** One immutable, reusable participation. Two references are distinct even for the same task. */
public final class TaskRef<C, I, O> {
    final Node<C> node;
    private TaskRef(Node<C> node) { this.node = node; }

    @SuppressWarnings("unchecked")
    public static <C, I, O> TaskRef<C, I, O> of(Task<C, I, O> task) {
        Objects.requireNonNull(task, "task");
        return new TaskRef<>(new Node<>("task", "TASK", (context, input, frame) -> task.execute(context, (I) input)));
    }
    public static <C, I, O> TaskRef<C, I, O> of(Composition<C, I, O> composition) {
        Objects.requireNonNull(composition, "composition");
        return new TaskRef<>(composition.definition());
    }
    private TaskRef<C, I, O> configure(Consumer<Policy.Mutable<C>> change) {
        return new TaskRef<>(node.policy(change));
    }
    public TaskRef<C, I, O> named(String name) { return new TaskRef<>(node.named(name)); }
    public TaskRef<C, I, O> within(Duration timeout) { return configure(p -> p.timeout = Policy.positive(timeout)); }
    public TaskRef<C, I, O> concurrently(int capacity) { return configure(p -> p.capacity = capacity); }
    public TaskRef<C, I, O> isolate(String name) { return configure(p -> p.isolation = Policy.name(name)); }
    public TaskRef<C, I, O> exclusively(Function<C, ?> key) { return configure(p -> p.exclusive = Objects.requireNonNull(key)); }
    public TaskRef<C, I, O> guard(String name) { return configure(p -> p.guard = Policy.name(name)); }
    public TaskRef<C, I, O> onFailure(FailureHandler<C> handler) { return configure(p -> p.handler = Objects.requireNonNull(handler)); }
    public TaskRef<C, I, O> retry(Consumer<RetryPolicy.Builder> definition) {
        var builder = new RetryPolicy.Builder(); definition.accept(builder);
        return configure(p -> p.retry = builder.build());
    }
    public TaskRef<C, I, O> cache(Consumer<CachePolicy.Builder<C>> definition) {
        var builder = new CachePolicy.Builder<C>(); definition.accept(builder);
        return configure(p -> p.cache = builder.build());
    }
    @SuppressWarnings("unchecked")
    public TaskRef<C, I, O> compensateWith(Compensation<C, I, O> compensation) {
        Objects.requireNonNull(compensation);
        return configure(p -> p.compensation = (context, input, output) ->
                compensation.compensate(context, (I) input, (O) output));
    }
}
