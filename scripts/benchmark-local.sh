#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
jdk_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
module_path=$(python3 scripts/project.py)
benchmark_output=${1:-java/benchmarks/target/local-results}
warmup=${BENCHMARK_WARMUP:-2000}
measured=${BENCHMARK_OPERATIONS:-5000}
forks=${BENCHMARK_FORKS:-2}
mkdir -p "$benchmark_output" java/benchmarks/target/local-classes
"${jdk_bin}javac" --release 25 -cp "$module_path" \
  -d java/benchmarks/target/local-classes java/benchmarks/src/main/java/io/orkestra/benchmarks/Workloads.java \
  java/benchmarks/src/main/java/io/orkestra/benchmarks/LocalMeasurement.java
{
  "${jdk_bin}java" -version 2>&1
  uname -a
  if command -v lscpu >/dev/null; then lscpu; fi
  printf 'warmup=%s measured=%s forks=%s\n' "$warmup" "$measured" "$forks"
  printf 'JVM flags: -Xms256m -Xmx256m\n'
} > "$benchmark_output/environment.txt"
printf 'scenario,clients,operations,ops_per_second,p50_us,p99_us,heap_used_delta_bytes,gc_collections,checksum\n' > "$benchmark_output/results.csv"
for scenario in direct sequential parallel cacheHit capacity; do
  for clients in 1 4; do
    for ((fork=1; fork<=forks; fork++)); do
      "${jdk_bin}java" -Xms256m -Xmx256m -cp "java/benchmarks/target/local-classes:$module_path" \
        io.orkestra.benchmarks.LocalMeasurement "$scenario" "$clients" "$warmup" "$measured" >> "$benchmark_output/results.csv"
    done
  done
done
printf 'Results: %s/results.csv\n' "$benchmark_output"
