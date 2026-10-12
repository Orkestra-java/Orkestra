# Library organization

`io.orkestra:orkestra-core` is a single, independently usable Maven artifact
and JPMS module (`io.orkestra.core`). Its only production requirement is JDK 25.

| Package under `io.orkestra.core` | Responsibility |
| --- | --- |
| root | `Orchestrator`, execution runtime, failure diagnostics |
| `composition` | immutable composition builder and typed results |
| `task` | tasks, conditions, compensation and failure handlers |
| `policy` | immutable cache and retry policies |
| `execution`, `exception` | execution results, options and diagnostics |
| `spi` | optional resource and event contracts |
| `local` | built-in cache, locks, breaker, history and event export |
| `internal` | implementation bridge; not exported by JPMS |

`new Orchestrator()` directly creates local resources. Consumers can override
resources through the builder without registering services. Compositions can
be reused across executions; application-provided tasks and shared state must
support the concurrency they request.

Logging, OpenTelemetry and Spring are separate optional artifacts activated by
`-Padapters`. Each depends on the core; the core does not depend on any adapter.
Maven's standard source/test layout applies to every artifact.

The [specification](specification.md) defines behavior. [ADR 0002](adr/0002-single-library.md)
records the current organization and supersedes the former three-layer split.
