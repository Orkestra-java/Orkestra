# Validation after architectural reorganization

Verified locally on 2026-10-12 with Temurin JDK 25 and Maven 3.9.16.
The accepted split is documented in [ADR 0001](adr/0001-layered-library.md).
Previous evidence remains in [the archive](archive/VALIDATION-before-layering.md).

## Build and contracts

The reactor under `java/` completed `verify` for all six artifacts. No tests
failed, errored, or were skipped:

| Module | JUnit Jupiter tests |
| --- | ---: |
| Domain | 2 |
| Application | 2 |
| Infrastructure | 70 |
| Logging adapter | 1 |
| OpenTelemetry adapter | 1 |
| Spring adapter | 1 |
| Total | 77 |

All 73 prior workflow/adapter contracts were preserved. New tests verify domain
definitions without an executor, duplicate participation, application execution
with explicitly injected ports and no infrastructure dependency, and the
missing-provider error. Existing tests continue to verify typed compilation,
parallel joining, cache cancellation, physical termination, compensation,
deadline handlers, resource cycles, shutdown, diagnostics, and observability.

The build used an existing distribution and copied dependency cache:

```sh
cd java
JAVA_HOME=/tmp/java25-study MAVEN_USER_HOME=/tmp/orkestra-wrapper \
  ./mvnw -o -Dmaven.repo.local=/tmp/orkestra-m2 verify
```

## Architecture and compatibility

The architecture check analyzed compiled dependencies with `jdeps` and inspected
JPMS descriptors. Domain depends only on JDK; application depends on domain/JDK;
infrastructure depends inward. The internal domain bridge is qualified-exported
only to application. A compiled negative control with a domain reference to
application was rejected; removing it restored the passing architecture check.

The six-artifact public-signature check passed against the new baseline, with
four Python checker tests passing. The author approved these package/artifact
changes before publication; the prior baseline is archived. Passing the new
baseline is not a claim of compatibility with the old monolithic packages.

## Consumers and artifacts

Both executable examples passed using the new explicit module path, including
JPMS discovery of the infrastructure resource provider. Infrastructure contract
tests and the local measurement runner also exercised classpath discovery.

Sources/Javadoc classifier JARs were generated for all six artifacts with JDK
tools. Javadoc markup/references were checked. The fallback writes deterministic
ZIP metadata, legal files, and SHA-256 checksums; this does not attach classifiers
to Maven or verify the network-dependent release profile.

The migrated dependency-free benchmark runner executed ten fresh-JVM smoke
cases, including workload-output checks. Short smoke runs verify tooling, not
architectural performance improvement. The earlier twenty-case
[performance baseline](performance/2026-10-12.md) is preserved as historical data.

## Remaining limits

The revised cold-build script copies the new source layout into a fresh directory
without build products, with empty wrapper/dependency caches. Its local attempt
failed at the Maven distribution download because DNS remained unavailable.
The CI jobs and manual JMH workflow have been updated but have not run remotely.
The Maven release and JMH profiles still require uncached plugins/dependencies;
prior offline failures and lack of network prevent claiming they passed here.

No remote publication, namespace ownership check, measured coverage percentage,
or production performance guarantee is asserted. Application's `Runtime` still
concentrates execution machinery; further decomposition can follow separately
without changing the accepted layer boundaries. Cooperative cancellation and
pending-work reporting retain their previously documented limitations.
