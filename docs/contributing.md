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
./gradlew :dispatch-core:test     # engine tests only — fast, no Docker
./gradlew :dispatch-api:bootRun   # run the app locally (H2, port 8080)
```

The suite is around 180 tests (as of 2026-08; run `./gradlew test` for the current count), and the timing-heavy
ones are driven by a controllable clock rather than sleeps, so the whole thing is fast and not
flaky.

## Test architecture

The tests that carry the most weight, and the pattern behind them:

- **`JobStoreContract`** (`dispatch-core`, test fixtures) — one suite of store-behaviour tests,
  run against all three stores: `InMemoryJobStoreTest`, `H2JdbcJobStoreTest`, and
  `PostgresJdbcJobStoreTest` all extend it. Every store must claim exclusively, order by priority
  then age, reject writes from a worker that lost its lease, and reclaim expired leases. This is
  what makes the implementations genuinely interchangeable — any new `JobStore` should extend the
  contract before anything else.
- **`ConcurrentInstancesIntegrationTest`** (`dispatch-postgres`) — two engine instances with
  separate connection pools against one containerised PostgreSQL, 300 jobs, asserting every job
  executed exactly once, that both instances did work, and that a job orphaned by a "crashed"
  instance is recovered by its peer.
- **`RetryAndDeadLetterTest`**, **`VisibilityTimeoutTest`**, **`ScheduledJobTest`**
  (`dispatch-core`) — driven by `MutableClock` (a test fixture), so "wait out the backoff" is an
  assignment rather than a `Thread.sleep`.
- **`GracefulShutdownTest`** — uses real wall-clock time, because draining is the one thing where
  actual elapsed time is the behaviour under test.
- **`JobSchemaTest`** (`dispatch-postgres`) — races twelve threads through schema creation ten
  times over: the regression test for the
  [concurrent-bootstrap race](architecture/reliability.md#schema-creation-is-a-race).
- **`JobApiTest`** / **`PostgresEndToEndTest`** (`dispatch-api`) — the HTTP surface against the
  in-memory store, and the full stack against containerised PostgreSQL.

## CI

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs `./gradlew build` on every push
and pull request to `main`: Temurin JDK 21, Gradle wrapper validation, and Testcontainers
starting its own PostgreSQL on the runner's Docker daemon (no `services:` block). Test reports
are uploaded as an artifact on failure. A newer push to the same branch or PR cancels the
running build.

## Module rules

The dependency arrow points inward only:

- `dispatch-core` depends on the JDK and SLF4J. No Spring, no JDBC, no JSON library. If a change
  to core needs one of those, the change belongs in another module.
- `dispatch-postgres` depends on `dispatch-core` and JDBC. Still no Spring.
- `dispatch-api` is the only module that knows Spring exists.

Compiler warnings are treated seriously: the build compiles with `-Xlint:all`. Keep new code
warning-clean.
