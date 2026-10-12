package io.orkestra.core.composition;

import io.orkestra.core.internal.Node;
import io.orkestra.core.internal.Policy;
import io.orkestra.core.task.Condition;
import io.orkestra.core.task.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Definition-time choice builder. All paths have one common output type. */
public final class Situation<C, I, O> {
    private final List<Path<C, I, O>> paths = new ArrayList<>();
    private TaskRef<C, I, O> fallback;
    private boolean frozen;
    public When when(Condition<C, I> condition) {
        mutable(); return new When(Objects.requireNonNull(condition));
    }
    public Situation<C, I, O> otherwise(Task<C, I, O> task) { return otherwise(TaskRef.of(task)); }
    public Situation<C, I, O> otherwise(Composition<C, I, O> composition) { return otherwise(TaskRef.of(composition)); }
    public Situation<C, I, O> otherwise(TaskRef<C, I, O> reference) { mutable(); fallback = Objects.requireNonNull(reference); return this; }
    public <V> Values<V> basedOn(Task<C, I, V> selector) { mutable(); return new Values<>(Objects.requireNonNull(selector)); }
    public <V> Values<V> basedOn(Function<I, V> selector) { return basedOn((context, input) -> selector.apply(input)); }
    private void mutable() { if (frozen) throw new IllegalStateException("Situation is already defined"); }
    void validate() { Objects.requireNonNull(fallback, "otherwise is required"); frozen = true; }
    TaskRef<C, I, O> choose(C context, I input) throws Exception {
        for (var path : paths) if (path.condition.test(context, input)) return path.reference;
        return fallback;
    }
    public final class When {
        private final Condition<C, I> condition;
        When(Condition<C, I> condition) { this.condition = condition; }
        public Situation<C, I, O> then(Task<C, I, O> task) { return then(TaskRef.of(task)); }
        public Situation<C, I, O> then(Composition<C, I, O> composition) { return then(TaskRef.of(composition)); }
        public Situation<C, I, O> then(TaskRef<C, I, O> ref) { mutable(); paths.add(new Path<>(condition, Objects.requireNonNull(ref))); return Situation.this; }
    }
    public final class Values<V> {
        private final Task<C, I, V> selector;
        private final List<V> expected = new ArrayList<>();
        private final List<TaskRef<C, I, O>> matches = new ArrayList<>();
        Values(Task<C, I, V> selector) { this.selector = selector; }
        public Match is(V expectedValue) { mutable(); return new Match(expectedValue); }
        public Situation<C, I, O> otherwise(Task<C, I, O> task) { return otherwise(TaskRef.of(task)); }
        public Situation<C, I, O> otherwise(Composition<C, I, O> composition) { return otherwise(TaskRef.of(composition)); }
        public Situation<C, I, O> otherwise(TaskRef<C, I, O> fallbackRef) {
            mutable(); Objects.requireNonNull(fallbackRef);
            var values = new ArrayList<>(expected); var references = List.copyOf(matches);
            // Capture the selector once per execution in a dedicated composition node.
            Node<C> node = new Node<>("value-choice", "DECISION", (context, input, frame) -> {
                @SuppressWarnings("unchecked") I typedInput = (I) input;
                V actual = selector.execute(context, typedInput);
                TaskRef<C, I, O> selected = fallbackRef;
                for (int index = 0; index < values.size(); index++) {
                    if (Objects.equals(values.get(index), actual)) { selected = references.get(index); break; }
                }
                frame.event("DECISION_PATH", selected.node.name, null);
                return frame.execute(selected.node, context, input);
            });
            fallback = TaskRef.of(new Composition<C, I, O>(List.of(node), "value-choice", new Policy<>(), Integer.MAX_VALUE));
            return Situation.this;
        }
        public final class Match {
            private final V expectedValue;
            Match(V expectedValue) { this.expectedValue = expectedValue; }
            public Values<V> then(Task<C, I, O> task) { return then(TaskRef.of(task)); }
            public Values<V> then(Composition<C, I, O> composition) { return then(TaskRef.of(composition)); }
            public Values<V> then(TaskRef<C, I, O> reference) { mutable(); expected.add(expectedValue); matches.add(Objects.requireNonNull(reference)); return Values.this; }
        }
    }
    private record Path<C, I, O>(Condition<C, I> condition, TaskRef<C, I, O> reference) {}
}
