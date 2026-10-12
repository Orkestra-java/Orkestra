package io.orkestra.benchmarks;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
public class WorkflowBenchmark {
    private Workloads workloads;
    @Setup(Level.Trial) public void setup() throws Exception { workloads = new Workloads(); }
    @TearDown(Level.Trial) public void close() { workloads.close(); }
    @Benchmark public long direct() { return workloads.direct(ThreadLocalRandom.current().nextLong()); }
    @Benchmark public long sequential() throws Exception { return workloads.sequential(ThreadLocalRandom.current().nextLong()); }
    @Benchmark public long parallel() throws Exception { return workloads.parallel(ThreadLocalRandom.current().nextLong()); }
    @Benchmark public long cacheHit() throws Exception { return workloads.cacheHit(); }
    @Benchmark public long capacity() throws Exception { return workloads.capacity(ThreadLocalRandom.current().nextLong()); }
}
