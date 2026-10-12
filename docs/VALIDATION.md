# Validation — single library candidate

Verified locally on 2026-10-12 with Temurin JDK 25.0.4.1 and Maven 3.9.16,
using the existing dependency cache:

- Default `verify`: **74 core tests passed**, no failures or skipped tests.
- `-Padapters verify`: **77 tests passed**, including logging, OpenTelemetry
  and Spring integration contracts. This was repeated after removing generated
  module directories; the rebuilt JARs contain no former layered packages.
- Default construction works without service discovery, including with an
  empty context class loader. Custom resource injection is also tested.

The default reactor builds only `orkestra-core`. Its production module requires
only `java.base`; JUnit is a test dependency. Framework dependencies stay in
optional adapters. Workflow contracts cover concurrency, policies, cancellation,
compensation, diagnostics and bounded shutdown.

Additional checks passed:

- Both executable examples run with the core JAR on the module path; the basic
  consumer also runs on the classpath.
- Signature baseline checks pass for the core and all optional adapters;
  all four checker tests pass.
- All four binary JARs contain the repository's `LICENSE` and `NOTICE`.
  The JDK fallback generated eight sources/Javadoc classifier JARs, with
  legal files and verified SHA-256 checksums. This does not validate the
  Maven `release` profile.
- Maintained documentation links, Maven XML and shell syntax checks pass.
Remote publication is not configured. Remote CI, uncached downloads, JMH and the
Maven `release` profile have not been verified in this network-restricted workspace.
