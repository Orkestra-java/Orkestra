# Release preparation

## Build candidates

Use JDK 25 and a network-connected machine:

```sh
(cd java && ./mvnw --batch-mode -Prelease,adapters verify)
python3 scripts/test_check_api.py
python3 scripts/check-api.py --all
```

The `release` profile attaches `*-sources.jar` and `*-javadoc.jar` to each
of the four artifacts. It uses the official
[Maven Source Plugin](https://maven.apache.org/plugins/maven-source-plugin/usage.html)
and [Maven Javadoc Plugin](https://maven.apache.org/plugins/maven-javadoc-plugin/usage.html).
Javadoc validates markup and references; missing comment warnings are excluded.
Binary JARs contain `LICENSE` and `NOTICE`.

`scripts/cold-build.sh` creates fresh directories for the Maven distribution
and dependency repository plus a fresh source copy without build products,
runs this profile, checks API compatibility, and
executes both examples. The `cold-build` CI job runs it without restoring Maven
caches and retains candidates as workflow artifacts. Remote CI results remain
unverified until that job actually runs. A local attempt failed at downloading
the Maven distribution because DNS was unavailable.

## Compatibility baseline

`api/0.1.0.json` captures public and protected declarations and JVM descriptors
of public types in exported packages, plus JPMS exports and dependencies.
`scripts/check-api.py` rejects removed types/members, changed declarations or
descriptors, removed module directives, and new abstract obligations. Concrete
additions are allowed. The CI build checks this baseline.

For an approved API addition, update and review the baseline in the same change:

```sh
python3 scripts/check-api.py --write --all
```

Do not rewrite the baseline solely to silence an unexpected failure. Changes
that break compatibility need a deliberate version and migration decision.
The baseline represents the single-library candidate, not a published release.
Previous baselines are preserved in `docs/archive/`; see [migration](MIGRATION.md). This is a conservative signature check: behavior, overload ambiguity,
annotations, serialized forms, and all JVM linkage rules still need contract
tests and review. Four tests verify the checker's rejection/acceptance rules.

## Local JDK fallback

When Maven source/Javadoc plugins are unavailable, generate local candidates
using the compiled modules and their runtime dependency JARs:

```sh
python3 scripts/package-docs.py --dependency-path "/path/to/dependency.jar:/path/to/another.jar"
```

Use the platform path separator. Include OpenTelemetry API/context and Spring
runtime dependencies for the optional modules. The script validates Javadoc,
packs deterministic ZIP entries with legal files, and writes SHA-256 checksums.
It generates files in each module's `target/`; it does not attach artifacts to
Maven or replace verification of the `release` profile. The fallback produces eight classifier JARs when all four artifacts are built.

## Before publication

Remote publication is a separate action. Verify namespace ownership, repository
and project URLs, SCM/developer metadata, version, signing, and the publication
repository against the intended account. None of those details are fabricated
in the POM. No deploy destination or automated remote publication is configured.
