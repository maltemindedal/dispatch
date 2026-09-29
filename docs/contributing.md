# Contributing

## Prerequisites

- **A JDK.** The build declares a Java 21 toolchain; the foojay resolver plugin (see
  [`settings.gradle.kts`](../settings.gradle.kts)) downloads one automatically if needed.
- **Docker**, for the Testcontainers-based integration tests in `dispatch-postgres` and
  `dispatch-api`. The `dispatch-core` tests need no Docker at all.

## Build and test

```bash
./gradlew build                   # compile + every test in every module
./gradlew test                    # tests only
./gradlew :dispatch-core:test     # engine tests only, fast, no Docker
./gradlew :dispatch-api:bootRun   # run the app locally (H2, port 8080)
```

The suite has a few hundred tests (run `./gradlew test` for the current count). The
timing-heavy tests use a controllable clock instead of sleeps, which keeps the suite fast and
avoids flaky timing.

## Dependency updates

[`.github/dependabot.yml`](../.github/dependabot.yml) proposes updates weekly for the GitHub
Actions (which are pinned to commit SHAs), the Gradle wrapper and version catalog, and the
PostgreSQL image in `docker-compose.yml`, each a week after release. Two things it cannot do for
you: the PostgreSQL image name is also written in two test files (`PostgresTestSupport` and
`PostgresEndToEndTest`) and has to move with the compose file, and framework major versions
(Spring Boot) are ignored on purpose because they are migrations.

## Upgrading Gradle

The wrapper pins the checksum of the Gradle distribution (`distributionSha256Sum` in
[`gradle/wrapper/gradle-wrapper.properties`](../gradle/wrapper/gradle-wrapper.properties)), so a
download that does not match fails instead of running. That means the pin has to move with the
version. Take the checksum from <https://gradle.org/release-checksums/> and pass it:

```bash
./gradlew wrapper --gradle-version <version> --distribution-type bin \
    --gradle-distribution-sha256-sum <sha256 of gradle-<version>-bin.zip>
```

Only `gradle-wrapper.properties` should change; if the wrapper jar or scripts change too, look at
why before committing.

## Test architecture

These tests cover the main contracts and execution paths:

- **`JobStoreContract`** (`dispatch-core`, test fixtures): one suite of store-behaviour tests,
  run against all three stores: `InMemoryJobStoreTest`, `H2JdbcJobStoreTest`, and
  `PostgresJdbcJobStoreTest` all extend it. Every store must claim exclusively, order by priority
  then age, reject writes from a worker that lost its lease, and reclaim expired leases. This is
  what makes the adapters interchangeable. Any new `JobRows` adapter should extend the
  contract before anything else.
- **`ConcurrentInstancesIntegrationTest`** (`dispatch-postgres`): two engine instances with
  separate connection pools against one containerised PostgreSQL, 300 jobs, asserting every job
  executed exactly once, that both instances did work, and that a job orphaned by a "crashed"
  instance is recovered by its peer.
- **`PriorityOrderingTest`**, **`DispatchCycleTest`** (`dispatch-core`): never start a
  dispatcher. They call `JobQueue.dispatchOnce()`, which runs one claim-and-dispatch cycle on the
  calling thread and returns what it claimed, then `awaitCompletion` on that batch. Assertions land
  on returned values instead of on how fast a background thread got somewhere; the whole priority
  suite runs in about ten milliseconds with no polling and no timing to tune. Prefer this shape for
  anything about *what* the engine does, and leave a running dispatcher to tests about *how it
  runs*.
- **`RetryAndDeadLetterTest`**, **`VisibilityTimeoutTest`**, **`ScheduledJobTest`**
  (`dispatch-core`): use `MutableClock` (a test fixture), so "wait out the backoff" is an
  assignment rather than a `Thread.sleep`.
- **`GracefulShutdownTest`**: uses real wall-clock time because draining is the one case where
  actual elapsed time is the behaviour under test.
- **`JobSchemaTest`** (`dispatch-postgres`): races twelve threads through schema creation ten
  times over: the regression test for the
  [concurrent-bootstrap race](architecture/reliability.md#schema-creation-is-a-race).
- **`JobApiTest`** / **`PostgresEndToEndTest`** (`dispatch-api`): test the HTTP endpoints against the
  in-memory store, and the full stack against containerised PostgreSQL.
- **`RestContractTest`** / **`StatsContractTest`** (`dispatch-api`): pin the wire contract byte for
  byte over real HTTP: exact JSON, status and content type of every success and error body. Where
  `JobApiTest` checks fields, these catch what an upgrade of Jackson, Spring or Tomcat can change
  without touching a field: key order, number formatting, escaping, omitted nulls. If you change a
  response on purpose, update the expected text here in the same commit; the placeholders (`<id>`,
  `<ts>`) stand for values the server generates.

## CI

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs `./gradlew build` on every push
and pull request to `main`: Temurin JDK 21, Gradle wrapper validation, and Testcontainers
starting its own PostgreSQL on the runner's Docker daemon (no `services:` block). Test reports
are uploaded as an artifact on failure. A newer push to the same branch or PR cancels the
running build. The actions are pinned to full commit SHAs (the release is in a comment beside
each), so upgrading one is a deliberate edit.

## Module rules

The dependency arrow points inward only:

- `dispatch-core` depends on the JDK and SLF4J. No Spring, no JDBC, no JSON library. If a change
  to core needs one of those, the change belongs in another module.
- `dispatch-postgres` depends on `dispatch-core` and JDBC. Still no Spring.
- `dispatch-api` is the only module that knows Spring exists.

Compiler warnings fail the build: it compiles with `-Xlint:all -Werror`, and the tree is at zero
warnings. Fix the cause rather than adding a `@SuppressWarnings`, unless the suppression is
genuinely the right answer for that line and says why.
