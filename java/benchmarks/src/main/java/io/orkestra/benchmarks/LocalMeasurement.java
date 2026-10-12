package io.orkestra.benchmarks;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;

/** Diagnostic baseline only; use JMH for controlled performance comparisons. */
public final class LocalMeasurement {
    private static volatile long consumed;
    private record Sample(long[] latencies, long checksum) {}
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("scenario clients warmup-per-client measured-per-client");
        String scenario = args[0];
        if (!Set.of("direct", "sequential", "parallel", "cacheHit", "capacity").contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        int clients = Integer.parseInt(args[1]), warmup = Integer.parseInt(args[2]), measured = Integer.parseInt(args[3]);
        if (clients < 1 || warmup < 1 || measured < 1) throw new IllegalArgumentException("Counts must be positive");
        try (var work = new Workloads(); var pool = Executors.newFixedThreadPool(clients)) {
            long probe = 123456789L;
            if (work.direct(probe) != work.sequential(probe)
                    || work.parallel(probe) != 4 * Workloads.mix(probe)
                    || work.capacity(probe) != Workloads.mix(probe)
                    || work.cacheHit() != Workloads.mix(7L)) throw new AssertionError("Invalid benchmark workload");
            run(pool, work, scenario, clients, warmup);
            long heapBefore = heap(), gcBefore = gcCount();
            long start = System.nanoTime();
            List<Sample> samples = run(pool, work, scenario, clients, measured);
            long elapsed = System.nanoTime() - start;
            long heapDelta = heap() - heapBefore, collections = gcCount() - gcBefore;
            long[] latencies = new long[Math.multiplyExact(clients, measured)];
            for (int i = 0; i < samples.size(); i++) {
                System.arraycopy(samples.get(i).latencies, 0, latencies, i * measured, measured);
                consumed ^= samples.get(i).checksum;
            }
            Arrays.sort(latencies);
            System.out.printf(Locale.ROOT,
                    "%s,%d,%d,%.2f,%.2f,%.2f,%d,%d,%d%n", scenario, clients, latencies.length,
                    latencies.length / (elapsed / 1e9), percentile(latencies, 0.50) / 1000.0,
                    percentile(latencies, 0.99) / 1000.0, heapDelta, collections, consumed);
        }
    }
    private static List<Sample> run(ExecutorService pool, Workloads work, String scenario, int clients, int count) throws Exception {
        var ready = new CountDownLatch(clients);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Sample>>();
        for (int client = 0; client < clients; client++) {
            final int seed = client;
            futures.add(pool.submit(() -> {
                long[] latencies = new long[count];
                long checksum = seed;
                var random = new SplittableRandom(seed);
                ready.countDown(); start.await();
                for (int operation = 0; operation < count; operation++) {
                    long input = random.nextLong(), begin = System.nanoTime();
                    checksum ^= switch (scenario) {
                        case "direct" -> work.direct(input);
                        case "sequential" -> work.sequential(input);
                        case "parallel" -> work.parallel(input);
                        case "cacheHit" -> work.cacheHit();
                        case "capacity" -> work.capacity(input);
                        default -> throw new AssertionError(scenario);
                    };
                    latencies[operation] = System.nanoTime() - begin;
                }
                return new Sample(latencies, checksum);
            }));
        }
        ready.await(); start.countDown();
        var results = new ArrayList<Sample>();
        for (var future : futures) results.add(future.get());
        return results;
    }
    private static long percentile(long[] values, double percentile) { return values[(int) Math.ceil(values.length * percentile) - 1]; }
    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum(); }
}
