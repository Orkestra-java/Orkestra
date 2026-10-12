package io.orkestra.core.composition;

import io.orkestra.core.exception.RequirementException;
import io.orkestra.core.internal.Node;
import io.orkestra.core.internal.Policy;
import io.orkestra.core.policy.CachePolicy;
import io.orkestra.core.policy.RetryPolicy;
import io.orkestra.core.task.Compensation;
import io.orkestra.core.task.Condition;
import io.orkestra.core.task.FailureHandler;
import io.orkestra.core.task.Task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class Compositions {
    private Compositions() {}
    public static <C, I> Builder<C, I, I> create() {
        return new Builder<>(List.of(), null, new Policy<>(), Integer.MAX_VALUE);
    }

    public static class Builder<C, I, Current> {
        final List<Node<C>> steps;
        final String name;
        final Policy<C> policy;
        final int groupLimit;
        Builder(List<Node<C>> steps, String name, Policy<C> policy, int groupLimit) {
            this.steps = List.copyOf(steps); this.name = name; this.policy = policy; this.groupLimit = groupLimit;
        }
        public Builder<C, I, Current> named(String name) {
            return new Builder<>(steps, Policy.name(name), policy, groupLimit);
        }
        public Builder<C, I, Current> within(Duration duration) {
            return new Builder<>(steps, name, policy.change(p -> p.timeout = Policy.positive(duration)), groupLimit);
        }
        public Builder<C, I, Current> onFailure(FailureHandler<C> handler) {
            return new Builder<>(steps, name, policy.change(p -> p.handler = Objects.requireNonNull(handler)), groupLimit);
        }
        /** Per-execution limit for a forEach definition, configured before its first task. */
        public Builder<C, I, Current> concurrently(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
            return new Builder<>(steps, name, policy, capacity);
        }
        public <O> TaskBuilder<C, I, Current, O> perform(Task<C, Current, O> task) {
            return perform(TaskRef.of(task));
        }
        public <O> TaskBuilder<C, I, Current, O> perform(TaskRef<C, Current, O> reference) {
            return append(Objects.requireNonNull(reference).node);
        }
        public <O> TaskBuilder<C, I, Current, O> perform(Composition<C, Current, O> composition) {
            return append(Objects.requireNonNull(composition).definition());
        }
        <O> TaskBuilder<C, I, Current, O> append(Node<C> step) {
            var next = new ArrayList<>(steps); next.add(step);
            return new TaskBuilder<>(next, name, policy, groupLimit);
        }
        @SuppressWarnings("unchecked")
        public TaskBuilder<C, I, Current, Current> require(Condition<C, Current> condition) {
            return require(condition, RequirementException::new);
        }
        @SuppressWarnings("unchecked")
        public TaskBuilder<C, I, Current, Current> require(
                Condition<C, Current> condition, Supplier<? extends Exception> rejection) {
            Objects.requireNonNull(condition); Objects.requireNonNull(rejection);
            return append(new Node<>("require", "REQUIRE", (context, input, frame) -> {
                if (!condition.test(context, (Current) input)) throw Objects.requireNonNull(rejection.get());
                return input;
            }));
        }
        public TaskBuilder<C, I, Current, Current> waitFor(Duration delay) {
            if (Objects.requireNonNull(delay).isNegative()) throw new IllegalArgumentException("Negative delay");
            return append(new Node<>("wait", "WAIT", (context, input, frame) -> {
                frame.pause(delay); return input;
            }));
        }
        public AfterParallel<C, I, Current> parallel(Consumer<ParallelGroup<C, Current>> definition) {
            var group = new ParallelGroup<C, Current>(); definition.accept(group); group.freeze();
            return new AfterParallel<>(this, group);
        }
        @SuppressWarnings("unchecked")
        public <O> TaskBuilder<C, I, Current, O> situation(
                Function<Situation<C, Current, O>, Situation<C, Current, O>> definition) {
            var choice = Objects.requireNonNull(definition.apply(new Situation<>())); choice.validate();
            return append(new Node<>("situation", "DECISION", (context, input, frame) -> {
                var selected = choice.choose(context, (Current) input);
                frame.event("DECISION_PATH", selected.node.name, null);
                return frame.execute(selected.node, context, input);
            }));
        }
        @SuppressWarnings("unchecked")
        public <Item, O> TaskBuilder<C, I, Current, List<O>> forEach(
                Function<Current, ? extends Iterable<Item>> items,
                Function<Builder<C, Item, Item>, ? extends Builder<C, Item, O>> definition) {
            Objects.requireNonNull(items);
            var iteration = Objects.requireNonNull(definition.apply(Compositions.<C, Item>create())).build();
            return append(new Node<>("forEach", "ITERATION", (context, input, frame) -> {
                var jobs = new ArrayList<java.util.concurrent.Callable<Object>>();
                int index = 0;
                for (var item : Objects.requireNonNull(items.apply((Current) input))) {
                    frame.check();
                    final int itemIndex = index++;
                    jobs.add(() -> frame.execute(iteration.definition().named("item-" + itemIndex), context, item));
                }
                var outputs = frame.parallel(jobs, iteration.groupLimit);
                return java.util.Collections.unmodifiableList(new ArrayList<>(outputs));
            }));
        }
        public <O> TaskBuilder<C, I, Current, O> withCompensation(
                Function<Builder<C, Current, Current>, ? extends Builder<C, Current, O>> definition) {
            var composition = Objects.requireNonNull(definition.apply(Compositions.<C, Current>create())).build();
            var node = composition.definition();
            return append(new Node<>(node.identity, node.name, "COMPENSATION_GROUP", node.operation, node.policy, true));
        }
        public Composition<C, I, Current> build() {
            return new Composition<>(steps, name, policy, groupLimit);
        }
    }

    /** Keeps the original input type of the last participation for compensation. */
    public static final class TaskBuilder<C, I, Previous, Current> extends Builder<C, I, Current> {
        TaskBuilder(List<Node<C>> steps, String name, Policy<C> policy, int groupLimit) {
            super(steps, name, policy, groupLimit);
        }
        private TaskBuilder<C, I, Previous, Current> replace(Node<C> node) {
            var next = new ArrayList<>(steps); next.set(next.size() - 1, node);
            return new TaskBuilder<>(next, name, policy, groupLimit);
        }
        private TaskBuilder<C, I, Previous, Current> configure(Consumer<Policy.Mutable<C>> change) {
            return replace(steps.getLast().policy(change));
        }
        @Override public TaskBuilder<C, I, Previous, Current> named(String name) { return replace(steps.getLast().named(name)); }
        @Override public TaskBuilder<C, I, Previous, Current> within(Duration duration) { return configure(p -> p.timeout = Policy.positive(duration)); }
        @Override public TaskBuilder<C, I, Previous, Current> onFailure(FailureHandler<C> handler) { return configure(p -> p.handler = Objects.requireNonNull(handler)); }
        @Override public TaskBuilder<C, I, Previous, Current> concurrently(int capacity) { return configure(p -> p.capacity = capacity); }
        public TaskBuilder<C, I, Previous, Current> isolate(String name) { return configure(p -> p.isolation = Policy.name(name)); }
        public TaskBuilder<C, I, Previous, Current> exclusively(Function<C, ?> key) { return configure(p -> p.exclusive = Objects.requireNonNull(key)); }
        public TaskBuilder<C, I, Previous, Current> guard(String name) { return configure(p -> p.guard = Policy.name(name)); }
        public TaskBuilder<C, I, Previous, Current> retry(Consumer<RetryPolicy.Builder> definition) {
            var builder = new RetryPolicy.Builder(); definition.accept(builder); return configure(p -> p.retry = builder.build());
        }
        public TaskBuilder<C, I, Previous, Current> cache(Consumer<CachePolicy.Builder<C>> definition) {
            var builder = new CachePolicy.Builder<C>(); definition.accept(builder); return configure(p -> p.cache = builder.build());
        }
        @SuppressWarnings("unchecked")
        public TaskBuilder<C, I, Previous, Current> compensateWith(Compensation<C, Previous, Current> action) {
            Objects.requireNonNull(action);
            return configure(p -> p.compensation = (context, input, output) -> action.compensate(context, (Previous) input, (Current) output));
        }
    }

    public static final class AfterParallel<C, I, Input> {
        private final Builder<C, I, Input> parent;
        private final ParallelGroup<C, Input> group;
        AfterParallel(Builder<C, I, Input> parent, ParallelGroup<C, Input> group) { this.parent = parent; this.group = group; }
        public <O> TaskBuilder<C, I, Input, O> join(Join<C, O> join) {
            Objects.requireNonNull(join);
            var refs = List.copyOf(group.references);
            var node = new Node<C>("parallel", "PARALLEL", (context, input, frame) -> {
                var jobs = new ArrayList<java.util.concurrent.Callable<Object>>();
                for (var ref : refs) jobs.add(() -> frame.execute(ref.node, context, input));
                var outputs = frame.parallel(jobs, group.capacity);
                var values = new java.util.IdentityHashMap<TaskRef<?, ?, ?>, Object>();
                for (int index = 0; index < refs.size(); index++) values.put(refs.get(index), outputs.get(index));
                frame.check(); frame.event("JOIN", "join", null);
                return join.combine(context, new Results(values));
            }).policy(p -> { p.timeout = group.timeout; p.handler = group.handler; });
            return parent.append(node);
        }
    }

    public static final class ParallelGroup<C, I> {
        private final List<TaskRef<C, I, ?>> references = new ArrayList<>();
        private int capacity = Integer.MAX_VALUE;
        private Duration timeout;
        private FailureHandler<C> handler;
        private boolean frozen;
        private void mutable() { if (frozen) throw new IllegalStateException("Group is already defined"); }
        public ParallelGroup<C, I> concurrently(int capacity) {
            mutable(); if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive"); this.capacity = capacity; return this;
        }
        public ParallelGroup<C, I> within(Duration timeout) { mutable(); this.timeout = Policy.positive(timeout); return this; }
        public ParallelGroup<C, I> onFailure(FailureHandler<C> handler) { mutable(); this.handler = Objects.requireNonNull(handler); return this; }
        public <O> ParallelGroup<C, I> perform(Task<C, I, O> task) { return perform(TaskRef.of(task)); }
        public <O> ParallelGroup<C, I> perform(Composition<C, I, O> composition) { return perform(TaskRef.of(composition)); }
        public <O> ParallelGroup<C, I> perform(TaskRef<C, I, O> reference) {
            mutable(); Objects.requireNonNull(reference);
            if (references.stream().anyMatch(existing -> existing == reference)) throw new IllegalArgumentException("Duplicate participation reference");
            references.add(reference); return this;
        }
        void freeze() { frozen = true; }
    }
}
