package io.orkestra.benchmarks;

import io.orkestra.core.Orchestrator;
import io.orkestra.core.composition.Composition;
import io.orkestra.core.composition.Compositions;
import io.orkestra.core.composition.TaskRef;
import io.orkestra.core.task.Task;

import java.time.Duration;

/** Shared definitions for JMH and the dependency-free local measurement runner. */
public final class Workloads implements AutoCloseable {
    private final Orchestrator coordinator = new Orchestrator();
    private final Composition<Void, Long, Long> sequence;
    private final Composition<Void, Long, Long> parallel;
    private final Composition<Long, Long, Long> cached;
    private final Composition<Void, Long, Long> limited;

    public Workloads() throws Exception {
        Task<Void, Long, Long> task = (context, input) -> mix(input);
        var chain = Compositions.<Void, Long>create();
        for (int index = 0; index < 16; index++) chain = chain.perform(task);
        sequence = chain.build();
        var a = TaskRef.of(task);
        var b = TaskRef.of(task);
        var c = TaskRef.of(task);
        var d = TaskRef.of(task);
        parallel = Compositions.<Void, Long>create()
                .parallel(group -> group.perform(a).perform(b).perform(c).perform(d))
                .join((context, results) -> results.get(a) + results.get(b) + results.get(c) + results.get(d))
                .build();
        cached = Compositions.<Long, Long>create().perform((context, input) -> mix(input))
                .cache(cache -> cache.key(context -> context).expiresAfter(Duration.ofHours(1))).build();
        limited = Compositions.<Void, Long>create().perform(TaskRef.of(task).concurrently(4)).build();
        cacheHit();
    }

    public static long mix(long value) {
        for (int index = 0; index < 32; index++) value = Long.rotateLeft(value ^ 0x9E3779B97F4A7C15L, 13) * 31 + index;
        return value;
    }
    public long direct(long input) { for (int index = 0; index < 16; index++) input = mix(input); return input; }
    public long sequential(long input) throws Exception { return coordinator.perform(sequence, null, input).output(); }
    public long parallel(long input) throws Exception { return coordinator.perform(parallel, null, input).output(); }
    public long cacheHit() throws Exception { return coordinator.perform(cached, 7L, 7L).output(); }
    public long capacity(long input) throws Exception { return coordinator.perform(limited, null, input).output(); }
    @Override public void close() { coordinator.close(); }
}
