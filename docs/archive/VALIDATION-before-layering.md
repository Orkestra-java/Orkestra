# Validation

Verified locally on 2026-10-12 with Temurin JDK 25 and Maven 3.9.16.

## Build and tests

The Maven Wrapper completed `verify` for all four modules, with no failures,
errors, or skipped tests:

| Module | JUnit Jupiter tests |
| --- | ---: |
| Core | 70 |
| Structured logging | 1 |
| OpenTelemetry | 1 |
| Spring lifecycle | 1 |
| Total | 73 |

Core tests cover typed pipelines and compiler rejection, parallel joining,
ordered iteration, policy scopes, capacity, locks, retry, breaker, cache
single-flight and cancellation, actual task termination, compensation,
deadline handler selection, diagnostic IDs, shutdown, and bounded events/history.
Adapter tests use a JUL handler, a real OpenTelemetry SDK/exporter, and a
Spring `GenericApplicationContext`.

Because shell DNS was unavailable, verification used an existing Maven
distribution and copied dependency cache in temporary writable directories:

```sh
JAVA_HOME=/tmp/java25-study MAVEN_USER_HOME=/tmp/orkestra-wrapper \
  ./mvnw -o -Dmaven.repo.local=/tmp/orkestra-m2 verify
```

This verifies the wrapper with a cached distribution and the reactor build;
it does not verify first-time downloads or a fresh network environment.
Remote CI is configured but has not been executed here.

## Packaged consumers

Both `examples/PlaceOrder.java` and `examples/WorkflowExample.java` ran
successfully against the packaged core on the Java module path. The latter
confirmed parallel results, ordered iteration, history, retries, and reverse
compensation after a deliberate failure.

All four JAR module descriptors were inspected. The core requires only
`java.base`; optional integrations declare their own dependencies. Each JAR
contains identical copies of the root `LICENSE` and `NOTICE`.
The publication copies of the specification, archive, and prototype match
their corresponding repository files.

## Review findings and limits

The parent/child resource contention reproduced during the previous review
is now rejected before waiting. Seven tests verify isolation and exclusive
keys, cache misses and hits, parallel descendants, resource release after
failure, and valid independent/sequential scopes. This guards the execution
tree; cycles across separate roots or manual recursive coordinator calls are
not detected.

Four additional Python tests passed for the API compatibility checker. The
checker passed against the versioned public/protected-signature and JPMS
baseline. Both packaged consumers still execute successfully.

Sources and Javadoc classifier JARs for all four modules were generated with
JDK tools, validating Javadoc markup/references and packaging legal files with
checksums. The `release` Maven profile is configured, but offline execution
failed resolving the uncached Maven Source Plugin 3.4.0. No claim is made that
this profile passed locally.

The local measurement runner executed 20 fresh-JVM cases, with warmup and
validated outputs. See [the dated report](performance/2026-10-12.md) for raw
results, environment, and measurement limitations. JMH compilation/execution
could not be validated: the offline build first failed resolving the uncached
Shade Plugin 3.6.2; JMH dependencies are also absent.

A cold-build script and CI job now use a fresh source copy, no existing build
products, and empty wrapper/dependency directories. The local attempt failed
downloading the Maven distribution because DNS was unavailable. The remote
CI jobs and manual JMH workflow are configured but have not run here.

There is no measured coverage percentage or published performance guarantee.
No remote publication or namespace ownership check was performed.

Tasks that ignore interruption can remain active after timeout. Pending-work
reports describe this situation; completed local tests do not prove external
service behavior, forced termination, durable execution, or restart recovery.
