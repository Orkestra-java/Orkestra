# ADR 0002 — One independently usable library

Status: accepted through the author's clarification on 2026-10-12.
Supersedes ADR 0001's requirement for separate architectural Maven layers.

The author clarified that the objective is to create a library. Three required
modules, resource-provider discovery, and application-style layering added
unnecessary complexity to consuming that library.

Use one `io.orkestra:orkestra-core` artifact and one `io.orkestra.core` Java
module. Organize source packages by responsibility: composition, task, policy,
execution, exceptions, SPI, runtime, and local implementations. Keep the model
bridge internal to the module. Default construction creates local resources
directly; consumers need neither service registration nor an infrastructure
module. Explicit custom resources remain an optional extension.

Logging, OpenTelemetry, and Spring adapters are retained under the optional
`adapters` Maven profile. The normal build and runtime require none of them.
Keep existing workflow behavior and tests, replacing the former missing-provider
error test with a test that default construction works without discovery.

Preserve the layered API baseline and architecture documents in the archive.
Update imports, examples, scripts, and the public signature baseline for this
pre-release simplification. Existing benchmark/release tools remain optional.
The public entry point is a Maven library dependency and a Java API; there is
no server, deployment setup, or container dependency required to consume it.
