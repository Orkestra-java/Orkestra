# ADR 0001 — Separate domain, application, and infrastructure

Status: superseded by [ADR 0002](0002-single-library.md) on 2026-10-12.
The following records the previous decision.

The author requested the organization used by the local TicketHub project and
explicitly selected architectural separation, including public package changes.
TicketHub places its Java reactor under `server/` and separates `domain`,
`application`, and `infrastructure`, with inward dependencies and architecture
tests. Orkestra will use `java/` for its library reactor.

- `java/domain`: immutable definitions, typed DSL, task contracts, policies,
  execution data, exceptions, and extension ports. Only JDK dependencies.
- `java/application`: execution, deadlines, cancellation, compensation,
  diagnostic association, and shutdown. Depends only on domain and the JDK.
- `java/infrastructure`: local cache, locks, breaker, history, asynchronous
  events, and the default resource provider.
- `java/infrastructure/adapters`: optional logging, OpenTelemetry, and Spring
  adapters, with separate artifacts and explicit framework dependencies.
- `java/benchmarks`: optional performance harness.
- `docs`: canonical documentation, ADRs, and preserved historical material.

Domain definitions will use an execution-scope port instead of referring to the
runtime implementation. Application consumes resource-provider ports; the local
provider is discovered via `ServiceLoader` or explicitly supplied to the builder.
No dependency from an inner layer to infrastructure is allowed. Internal
model bridges are qualified JPMS exports to application, not public user API.

Public imports, Java module names, and Maven artifact names change deliberately
before the first release. Preserve the previous signature baseline as an archive,
create a new reviewed baseline, and provide a migration map. Preserve workflow
behavior and all existing contract tests. Architecture checks must examine the
compiled dependencies and module declarations, not just folder names.

The root contains repository navigation, legal files, scripts, examples, and
CI. Maven Wrapper and the reactor belong to `java/`. Archived references and the
old publication bundle remain intact under `docs/archive/`; maintained docs have
one canonical copy. Remote publication and network validation remain separate.
