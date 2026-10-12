# Extension Guide

All adapters are optional. Use only `io.orkestra:orkestra-core` when local
execution and the core SPIs are sufficient. Versions below match the build's
`0.1.0-SNAPSHOT`; the artifacts have not been remotely published.

## JSON logs

Add `io.orkestra:orkestra-observability` and register a listener:

```java
var logs = new StructuredLogging(Logger.getLogger("orders"));
var orchestrator = Orchestrator.builder().events(logs).build();
```

Classes: `io.orkestra.observability.StructuredLogging`, `java.util.logging.Logger`.
JUL handlers, destinations, and retention belong to the host. The adapter emits
JSON with IDs, hierarchy, timestamps, duration, outcome, exception class, and
explicit attributes. It does not include exception messages or business payloads.

## OpenTelemetry

Add `io.orkestra:orkestra-opentelemetry`. Provide a configured `Tracer`:

```java
var spans = new OpenTelemetryEvents(tracer);
var orchestrator = Orchestrator.builder().events(spans).build();
```

The adapter creates child spans using the event hierarchy and records failure
status. It keeps at most 4096 active spans by default. For incoming request
parentage, supply an explicit `Function<ExecutionEvent, Context>` and capacity
through the three-argument constructor; look up the request context using the
correlation ID selected by the application. The default starts new root traces.
The adapter does not assume a thread-local request context on exporter threads.

SDK, sampling, OTLP exporters, and Grafana endpoints are configured by the host.
Close the coordinator, drain an optional asynchronous exporter, then close the
span adapter and the host-owned SDK. SDK APIs are used only in adapter tests;
the runtime adapter depends on the OpenTelemetry API/context.
See [OpenTelemetry Java API](https://opentelemetry.io/docs/languages/java/api/).

## Spring lifecycle

Add `io.orkestra:orkestra-spring` and declare the bridge as a bean:

```java
@Bean
Orchestrator orchestrator() {
    return Orchestrator.builder().compensationBudget(Duration.ofSeconds(5)).build();
}

@Bean
OrkestraLifecycle orkestraLifecycle(Orchestrator orchestrator) {
    return new OrkestraLifecycle(orchestrator, Duration.ofSeconds(10), 0);
}
```

`OrkestraLifecycle` is in `io.orkestra.spring`. It starts automatically, stops
the provided coordinator, and exposes its latest shutdown report. Configure
the server ingress at a higher Spring lifecycle phase so requests stop before
the coordinator. The bridge controls this library's lifecycle; the application
still controls its HTTP server and request admission. A stopped coordinator
requires a new instance rather than restarting the same lifecycle bean.
See [Spring SmartLifecycle](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html).

## Core providers

Contracts live in `io.orkestra.core.spi`:

| Contract | Local implementation | Registration |
| --- | --- | --- |
| `CacheStore` | `MemoryCache` | `builder.cache(name, store)` |
| `LockProvider` | `LocalLocks` | `builder.locks(provider)` |
| `CircuitBreaker` | `LocalCircuitBreaker` | `builder.breaker(name, breaker)` |
| `EventListener` | application callback | `builder.events(listener)` |
| `HistoryStore` | `MemoryHistory` | register as an event listener |

Custom providers must be thread-safe. Cache entries explicitly distinguish a
cached null from a miss; store only completed successes and implement TTL,
bounds, and invalidation. Keys must be stable and identify compatible outputs.
A named cache key is shared across users of that provider; task-local caches
have independent namespaces.

Lock acquisition must respect timeout and interruption. Leases release once
at actual body termination. Circuit permits belong to individual attempts and
must implement success/failure outcomes, including a half-open probe.
External provider resources remain application-owned.

## Bounded event export

```java
var history = new MemoryHistory(1000, 2000, Duration.ofHours(1));
var export = new AsyncEventListener(history, 512,
    AsyncEventListener.OverflowPolicy.DROP, history::markIncomplete);
var orchestrator = Orchestrator.builder().events(export).build();
```

The export queue is bounded. Check `dropped()`, `failures()`, `pending()`, and
`history.find(id).incomplete()` when interpreting an execution trace. Direct
listener failures are counted by `orchestrator.eventFailures()` and normally
preserve workflow execution. Strict history requires both the exporter's FAIL
policy and the coordinator's strict-events option. Close the coordinator first,
then call `export.close(budget)`; false means the exporter did not fully drain.
A blocking delegate remains responsible for honoring interruption.

## Optional resource factories

Default construction directly creates `io.orkestra.core.local.resources.LocalResources`.
To replace the resource factory, implement `io.orkestra.core.spi.ResourceProvider`
and pass it to `Orchestrator.builder().resources(provider)`. You can also override
individual caches, locks and breakers using the builder methods above.

Import `Orchestrator` from `io.orkestra.core`. No service registration is required.
