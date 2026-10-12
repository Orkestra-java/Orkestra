# Java library

Use JDK 25 and run Maven commands in this directory:

```sh
./mvnw test
./mvnw verify
./mvnw install
./mvnw -Padapters verify
```

The default build contains only `core/`, artifact `io.orkestra:orkestra-core`,
Java module `io.orkestra.core`. It has no external production dependencies.
`install` makes the library available to other local Maven projects.

The optional `adapters` profile adds:

| Directory | Artifact | Java module |
| --- | --- | --- |
| `adapters/logging/` | `orkestra-observability` | `io.orkestra.observability` |
| `adapters/opentelemetry/` | `orkestra-opentelemetry` | `io.orkestra.opentelemetry` |
| `adapters/spring/` | `orkestra-spring` | `io.orkestra.spring` |

All artifacts use group `io.orkestra`. Sources and tests follow Maven's
`src/main/java/` and `src/test/java/` conventions. External frameworks stay
in adapter POMs. Benchmarks (`-Pbenchmarks`) and source/Javadoc attachment
(`-Prelease`) are optional development tools.

See the [root README](../README.md) for a consumer example.
