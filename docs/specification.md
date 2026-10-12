# Orkestra specification

The Java 25 library executes immutable, reusable typed compositions inside the
consuming process. The library ships as one independently usable `orkestra-core` artifact and
`io.orkestra.core` Java module. See [ADR 0002](adr/0002-single-library.md).
Default resources are built in; no infrastructure module or service registration
is required. Framework integrations are optional Maven-profile modules.

## Definitions and flow

`Task<C,I,O>` consumes application context and input, returning an explicit output.
`Compositions.create()` builds a typed `Composition<C,I,O>`; each `perform`
consumes the previous output. An empty composition is the input identity.
Nullable outputs are valid. Definition immutability does not make user tasks,
contexts, or shared outputs thread-safe.

`TaskRef` identifies a participation by identity, independently of display names.
Parallel branches share input/context. References must be unique in their group;
`Results.get` rejects unrelated references. `parallel` requires `join` before
continuing or building, and join runs only after all branches succeed.

`situation` selects a typed path, requires `otherwise`, and supports conditions
or one evaluation of a value selector. `forEach` extracts items, uses an independent
typed iteration input, and returns outputs in input order, including null.
`require` preserves input or throws a supplied exception (`RequirementException`
by default). `waitFor` preserves input while respecting cancellation/deadlines.

## Policy scope and order

Policies chained after `perform` configure that participation. Initial builder
policies and built `Composition.within/onFailure` configure the composition.
Definitions and policies remain immutable. Group execution limits are configured
before the first item task. Retry/cache apply only to leaf tasks; applying them
to compositions, decisions, or requirements is rejected.

Policy order is fixed: cache/single-flight, deadline, capacity, exclusivity, retry,
and breaker permission per attempt. Waiting and retry delays count against
`within`; the effective deadline is the shortest enclosing deadline.

Task capacity spans executions of the same participation in one coordinator.
Isolation names share capacity and reject conflicting limits. Exclusivity is
per key/provider. Ancestor resource reacquisition is rejected before waiting,
including parallel descendants and cache misses; cache hits acquire no resources.
Cycles between independent executions or manual recursive coordinator calls
are not detected. Resources remain held until their owning operation physically exits.

Retry counts total attempts, requires an explicit `retryIf`, and uses a fixed
configured delay. Timeout, interruption, cancellation, breaker rejection, shutdown,
and fatal errors stop retry. The default breaker counts consecutive failures,
opens for a configured interval, and allows one half-open probe.

Cache stores successful outputs only, including null. Local memory storage has
bounded capacity (default 1024), monotonic TTL, and explicit invalidation.
Named stores must be registered. Keys must distinguish all result differences
and compatible output types. Concurrent subscribers share a loader; one leaving
does not cancel other subscribers, and the last leaving requests cancellation.
Invalidation removes stored values and does not cancel active loaders.
Cache and explicit compensation on the same participation are rejected.

## Failure and compensation

Success returns `Execution<O>` with ID, output, timestamps, and monotonic duration.
Failure throws an exception and never returns a success-shaped failure result.
The nearest applicable `onFailure` translates once after cleanup/compensation;
outer handlers do not translate again. An enclosing deadline belongs to its
owning scope, so descendant handlers do not intercept that timeout. A handler
cannot resume the flow. Handler failure/null preserves the original cause.

`withCompensation` collects explicit `compensateWith` actions after physical
success, with original context/input/output. Undo follows reverse completion
order, preserving sequential dependencies; independent parallel order is unspecified.
An explicit group compensation replaces its descendant registrations.
Compensation errors are aggregated, with an independent bounded cleanup budget.

Cancellation is cooperative. The runtime distinguishes a cancelled Future from
a stopped operation. It prevents later steps, requests interruption, and waits
within the cancellation budget. If work remains active, unsafe compensation is
skipped and reported; skipped actions are not automatically resumed later.
Shutdown rejects new work, waits for grace, then cancels and reports pending
execution IDs, work, and compensation IDs. No forced thread termination is promised.

`Failures.details` extracts diagnostics without a global registry. Fresh exceptions
with suppression enabled retain their type; shared/reused or suppression-disabled
exceptions receive `ExecutionFailureException`, preserving cause and occurrence ID.
Compensation failures and pending work remain available after translation.

## Ports, events, and lifecycle

The core defines resource/event/history SPIs, owns execution, and creates local
defaults directly per coordinator. Custom resources can be supplied explicitly.
The core depends only on the JDK. Logging, OpenTelemetry, and Spring are optional
adapters activated with the Maven `adapters` profile.

Events include execution/correlation/span hierarchy, names, timing, cause, and
explicitly selected attributes. Business context/output are not automatically
exported. Local history limits executions, events, and TTL. Asynchronous export
has a bounded queue, drop/failure counters, incomplete-history markers, and bounded
close/drain. Strict observer rejection requires explicit strict configuration.

The host owns business state and external SDK/store/exporter lifetimes. Spring
integration only coordinates lifecycle; ingress must stop before the coordinator.
Frontend, distributed workflow infrastructure, durable execution, replay, and
restart recovery remain outside this implementation. Apache-2.0 and `io.orkestra`
are approved; remote release remains a separate action.
