# Pre-release migration

The current candidate combines the former architectural layers into one library.
No version has been remotely published.

| Previous dependency or package | Current replacement |
| --- | --- |
| `orkestra-domain`, `orkestra-application`, `orkestra-infrastructure` | `orkestra-core` |
| JPMS `io.orkestra.domain`, `io.orkestra.application`, `io.orkestra.infrastructure` | `io.orkestra.core` |
| `io.orkestra.application.Orchestrator` | `io.orkestra.core.Orchestrator` |
| `io.orkestra.domain.composition` | `io.orkestra.core.composition` |
| `io.orkestra.domain.task`, `.policy`, `.execution`, `.exception` | corresponding `io.orkestra.core` packages |
| `io.orkestra.domain.port` | `io.orkestra.core.spi` |
| `io.orkestra.infrastructure` local resource packages | `io.orkestra.core.local` |
| `io.orkestra.infrastructure.logging.StructuredLogging` | `io.orkestra.observability.StructuredLogging` |

Update Maven dependencies and imports, then compile consumers. Remove the
resource-provider service-discovery setup when using defaults. Optional adapter
artifact names are unchanged; their source paths now live under `java/adapters/`.

The current signature baseline is `api/0.1.0.json`. Prior baselines and layered
architecture documents are preserved in [the archive](archive/README.md).
