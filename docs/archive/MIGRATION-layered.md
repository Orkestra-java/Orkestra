# Migration to layered modules

The author explicitly approved architectural separation and changes to public
packages. This candidate has not been published; workflow behavior remains the
same, but previous imports/module paths must be updated.

| Previous artifact/module | Current artifact/module |
| --- | --- |
| `orkestra-core` / `io.orkestra.core` | `orkestra-domain`, `orkestra-application`, `orkestra-infrastructure` / matching `io.orkestra.*` modules |
| logging, OpenTelemetry, Spring artifacts | unchanged artifact and Java module names; moved under `java/infrastructure/adapters/` |

For local defaults, depend on `io.orkestra:orkestra-infrastructure`. It transitively
includes domain/application. For an independent runtime with external ports,
depend on `orkestra-application` and supply a `ResourceProvider` explicitly.

| Previous imports | Current package |
| --- | --- |
| `io.orkestra.core.Composition`, `Compositions`, `TaskRef`, `Results`, `Join`, `Situation` | `io.orkestra.domain.composition` |
| `Task`, `Condition`, `Compensation`, `FailureHandler` | `io.orkestra.domain.task` |
| `CachePolicy`, `RetryPolicy` | `io.orkestra.domain.policy` |
| `Execution`, `ExecutionOptions`, `FailureDetails`, `FailureKind`, `ShutdownReport` | `io.orkestra.domain.execution` |
| workflow exception types | `io.orkestra.domain.exception` |
| `Orchestrator`, `Failures` | `io.orkestra.application` |
| SPI interfaces and `ExecutionEvent` | `io.orkestra.domain.port` |
| `MemoryCache`, `LocalLocks`, `LocalCircuitBreaker` | `io.orkestra.infrastructure.cache`, `.lock`, `.resilience` |
| `MemoryHistory`, `AsyncEventListener` | `io.orkestra.infrastructure.history`, `.events` |
| optional adapter classes | `io.orkestra.infrastructure.logging`, `.opentelemetry`, `.spring` |

Run Maven from `java/`; execute repository tools from the root.
`scripts/run-examples.sh` computes the explicit module path and includes the
infrastructure service provider. Service discovery works on the module path and
classpath; without that provider use `Orchestrator.builder().resources(provider)`.

The old public-signature baseline is preserved in
[the archive](archive/api-0.1.0-core.json). The new `api/0.1.0.json` captures this
approved pre-release boundary; this is an intentional incompatible reorganization,
not a claim of compatibility with the previous layout.
