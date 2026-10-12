# Repository Guidelines

## Project Structure & Module Organization

Orkestra is an embedded Java 25 library. `java/core/` produces the independently
usable `io.orkestra:orkestra-core` artifact. Organize packages under
`io.orkestra.core` by responsibility: composition, task, policy, execution,
exceptions, SPI, and local resources. Keep implementation bridges in `internal`.
Sources and tests use `src/main/java/` and `src/test/java/`.

`java/adapters/{logging,opentelemetry,spring}/` contains optional integrations;
`java/benchmarks/` contains optional measurements. `docs/` is canonical;
`docs/archive/` preserves history. Runnable consumers live in `examples/`,
repository tools in `scripts/`, and public signature baselines in `api/`.

## Build, Test, and Development Commands

Use JDK 25 and the Maven Wrapper under `java/`:

```sh
(cd java && ./mvnw test)
(cd java && ./mvnw verify)
(cd java && ./mvnw -Padapters verify)
python3 scripts/check-api.py
scripts/run-examples.sh
```

`test` runs core tests; `verify` also packages the core JAR. `-Padapters` includes
optional integrations. Run `check-api.py --all` after building adapters to check
all public signatures. `./mvnw install` installs local consumer dependencies.
Initial downloads require network access. Release and benchmark profiles are optional.

## Coding Style & Naming Conventions

Use four spaces, braces on declaration lines, `PascalCase` types, and `camelCase`
methods. Preserve approved vocabulary: `Composition`, `Task`, `perform`,
`withCompensation`, and `compensateWith`. Keep compositions and policies immutable
and group results typed. Keep framework dependencies in optional adapters.
The core must work with default construction and no service registration.
No formatter or linter is configured; follow `.editorconfig` and nearby code.

## Testing Guidelines

Use JUnit Jupiter and `*Test.java`. Coordinate concurrency with latches and
bounded waits. Cover resource release, physical termination, compensation order,
and pending shutdown work when changing execution behavior. Run
`python3 scripts/test_check_api.py` for signature-checker changes.
No numerical coverage threshold is configured.

## Commit & Pull Request Guidelines

Git history is unavailable in this workspace; use short imperative messages.
Describe changed behavior, rationale, validation, and limitations. Link relevant
issues and decisions. Review signature-baseline changes explicitly.

## Contributor Workflow

Update the specification before changing public contracts. Follow
`docs/adr/0002-single-library.md`; the former layered layout is historical.
Keep documentation concise and consumer-focused. License: Apache-2.0.
