# Performance measurements

This optional module keeps benchmark dependencies out of the library.
Definitions are built once and reused by all clients. Workloads cover a direct
16-operation baseline, an equivalent 16-step composition, four parallel CPU
branches and join, a prefilled cache hit, and a task with capacity four.
`LocalMeasurement` checks workload outputs before timing.

## JMH

The [OpenJDK JMH harness](https://github.com/openjdk/jmh) provides warmup,
measurement iterations, forks, and profilers. With network access:

```sh
(cd java && ./mvnw -Pbenchmarks -pl benchmarks -am package)
java -jar java/benchmarks/target/benchmarks.jar -t 1 -prof gc -rf json -rff java/benchmarks/target/jmh-1-thread.json
java -jar java/benchmarks/target/benchmarks.jar -t 4 -prof gc -rf json -rff java/benchmarks/target/jmh-4-threads.json
```

Defaults: three one-second warmup iterations, five one-second measurement
iterations, two JVM forks, 256 MiB fixed heap, throughput and sampled latency.
The GC profiler records allocation measurements; see the
[official profiler sample](https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_35_Profilers.java).
Run the manual `Benchmarks` CI workflow to retain JSON results. JMH dependencies
were unavailable locally, so this Maven profile and its remote execution have
not yet been validated. JVM/classpath packaging is isolated in the benchmark
module; the production library remains modular. The Shade Plugin is pinned to
[3.6.2](https://maven.apache.org/plugins/maven-shade-plugin/dependency-info.html),
and the JMH annotation processor is explicitly selected for JDK 25.

## Dependency-free local baseline

After building the Java reactor, execute from the repository root:

```sh
scripts/benchmark-local.sh
BENCHMARK_WARMUP=1000 BENCHMARK_OPERATIONS=2000 BENCHMARK_FORKS=2 scripts/benchmark-local.sh
```

Each scenario/client-count/fork runs in a separate JVM, with explicit warmup.
Reports contain operations/second, per-call p50/p99, GC collection count,
end-minus-start heap usage, consumed-result checksum, and machine/JDK metadata.
Files go to `java/benchmarks/target/local-results/`; pass another directory as the
first argument. The runner uses one or four caller platform threads; Orkestra
continues to create its own virtual threads internally.

This diagnostic runner includes clock reads and pool coordination; it is not
JMH. Heap usage delta can be negative and does **not** measure allocation per
operation. The cached task deliberately repeats a fixed compatible key/input.
CPU-only parallel tasks measure coordination cost, not network-I/O speedup.
Compare results only for equivalent workloads and environments. No pass/fail
performance threshold is established. The dated local report in
`docs/performance/` records the executed baseline, not a production guarantee.

The recorded 2026-10-12 baseline is historical. Re-run measurements against
the current core artifact before comparing performance changes.
