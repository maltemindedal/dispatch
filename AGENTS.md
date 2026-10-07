# AGENTS.md

dispatch is a teaching build of a durable job queue: a plain-Java engine (`dispatch-core`), a JDBC
adapter that claims with `SELECT ... FOR UPDATE SKIP LOCKED` (`dispatch-postgres`), and a Spring
Boot HTTP edge (`dispatch-api`). A change usually has to protect exclusive claiming and lease
fencing under concurrency, the single home of every storage rule, the byte-exact REST contract,
and the inward-only module arrow.

## Commands

Run from the repo root on a machine with a JDK and a running Docker daemon, as CI does.

- `./gradlew build`: the pre-PR gate and all of CI (one workflow, one step, JDK 21). Compiles with
  `-Xlint:all -Werror` and runs every test in every module. Needs Docker.
- `./gradlew :dispatch-core:test`: engine tests only, no Docker. A fast loop, not a gate.
- One class: `./gradlew :dispatch-core:test --tests 'dev.dispatch.core.engine.PriorityOrderingTest'`
- One method: `./gradlew :dispatch-core:test --tests 'dev.dispatch.core.engine.RetryAndDeadLetterTest.retriesUntilSuccess'`
- `--tests` matches class and method names, and globs such as `'*RestContractTest'`, never the
  `@DisplayName` text the report prints: that gives "No tests found".
- The build cache is on, so a test task with unchanged inputs replays its last result. Add
  `--rerun-tasks` to make a re-run real, for example when re-checking a flake.
- The Testcontainers classes in `dispatch-postgres` and `dispatch-api` fail rather than skip
  without Docker. If you cannot run them, say so in the PR body.
- Running the build inside a container with the host's Docker socket mounted (Docker Desktop):
  set `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`, or those classes fail with
  `Could not connect to Ryuk`.
- After editing `.github/dependabot.yml` or `.github/workflows/ci.yml`, validate them, since CI
  does not: `uvx check-jsonschema --builtin-schema vendor.dependabot .github/dependabot.yml` and
  `uvx check-jsonschema --builtin-schema vendor.github-workflows .github/workflows/ci.yml`.
- In a CI log, find the failure with `> Task .* FAILED`. A green build also prints `FAILED` inside
  test names, such as `promotes FAILED jobs once their backoff elapses PASSED`.

## Conventions

### Module boundary and dependencies

- `dispatch-core` uses only the JDK and SLF4J, `dispatch-postgres` adds JDBC but no Spring, and
  only `dispatch-api` knows Spring. The classpath keeps Spring and Jackson out of core, but nothing
  stops `java.sql`: it ships with the JDK and core has no `module-info.java`. Keep JDBC in
  `dispatch-postgres`, behind the `JobRows` seam, by hand.
- A new dependency needs its case in the PR body: it has to replace hand-rolled code (PR #7 turned
  down Error Prone on this bar). No broker, queue or scheduling library in any module, and no
  Flyway until a second schema version exists.
- `.github/dependabot.yml` has no `cooldown` blocks on purpose, so Dependabot's default delay
  applies. PR #15 removed a self-invented 7-day rule.

### Engine code

- Storage rules live once, in `JobStore` and `JobSelection`. `InMemoryJobRows` and `JdbcJobRows`
  only render a selection and write rows back. A new predicate or sort key goes in `JobSelection`
  (every order ends in the `ID` tiebreak), with its test in `JobStoreContract` so all three stores
  run it. Per-adapter copies had drifted apart before PR #6.
- Engine code reads time only from the injected `Clock`; `JobStore` methods take `now`.
- Guard shared state with `ReentrantLock` or `Semaphore`; main code has no `synchronized`. Handlers
  run on virtual threads, and on this Java 21 toolchain `synchronized` pins them (JDK 24 fixed it).
- Return refusals as values: `JobActionResult` (`Done`, `NotFound`, `WrongState`) for operator
  actions, `Optional.empty()` for a lost lease. Storage failures surface as `JobStoreException`.
- Add API, a state transition or a config key in the same change as the code path that uses it.
  Review has removed unused surface (`85fe8e0`) and speculative transitions (PR #11).
- Reuse the existing helper (`registry.require`, `JobState.isCancellable`) and import types instead
  of writing them fully qualified inline: both are repeat review findings.
- Spring Boot 4, Jackson 3 and Testcontainers 2 may postdate your training. Jackson lives in
  `tools.jackson.*` (`StringNode` replaced `TextNode`), Boot's test support is split into modules
  such as `spring-boot-resttestclient`, and the container class is
  `org.testcontainers.postgresql.PostgreSQLContainer`. Copy imports from an existing class in the
  module rather than from memory; versions are in `gradle/libs.versions.toml`.

### Tests

- Every test class and method carries a `@DisplayName` sentence. Assert with AssertJ.
- Behaviour every store shares goes in `JobStoreContract` (`dispatch-core/src/testFixtures`).
  JDBC-only behaviour gets an abstract base with `H2*` and `Postgres*` subclasses, as
  `JdbcScopeAtomicityTest` does.
- Test what the engine does through `JobQueue.dispatchOnce()` and the claim it returns, and pass
  time by advancing `MutableClock`. Virtual-thread start order says nothing about claim order:
  reading it that way caused both CI flakes so far. Keep a running dispatcher and Awaitility for
  tests about how the engine runs.

### Prose, commits and PRs

- Use the terms in `GLOSSARY.md`, and none of its _Avoid_ words, in code, docs and commit messages.
  Read it before naming anything to do with claims, leases, sweeps or retries; PR #10 renamed code
  to match it.
- Write docs, Javadoc and comments in plain, short sentences with no em dashes: the owner removed
  all 216 in `ffca785`.
- Commit subjects are imperative and sentence case, with no Conventional Commits prefix. The body
  explains why. A bug fix opens its body with `BUG FIX.`, lands with its regression test in the
  same commit, says the test was red before the fix, and gives the test count change
  (`325 -> 337 tests`). Docs the change touches go in the same commit.
- PR bodies follow `.github/pull_request_template.md` and say what was not verified locally and
  why. Merge with a merge commit (`gh pr merge --merge`), the repo's convention.

## Gotchas

- Files that change together. Only some pairs have a test, so check the rest by hand:
  - Engine defaults: `QueueConfig.Builder` or `ExponentialBackoffRetryPolicy`,
    `dispatch-api/src/main/resources/application.yml` (its bound values override the engine's
    under Spring; `ApplicationYmlDefaultsTest` pins the two), and `docs/reference/configuration.md`.
  - Lifecycle: the transition table and ASCII diagram in `JobState`, the mermaid diagram in
    `docs/architecture/job-lifecycle.md`, and the `CHECK` list in
    `dispatch-postgres/src/main/resources/db/jobs-schema.sql`. Only the last is tested, by
    `JobSchemaTest`, which needs Docker.
  - REST responses: the expected text in `RestContractTest` and `StatsContractTest`, and
    `docs/reference/api.md`.
  - Claim order: `JobSelection.CLAIM_ORDER` and the `idx_jobs_claim` index in `jobs-schema.sql`.
  - PostgreSQL image: `docker-compose.yml`, `PostgresTestSupport.java` and
    `PostgresEndToEndTest.java`. Dependabot bumps only the compose file.
- `jobs-schema.sql` is applied idempotently at startup with no migration tool, so
  `CREATE TABLE IF NOT EXISTS` never alters an existing table. `JobSchema.readStatements` drops
  whole-line `--` comments and splits on `;`, so a `;` inside a string literal or a trailing
  comment cuts a statement in two.
- H2 runs the same SQL with coarser locking: a contending reader gets an empty claim, not the next
  unlocked rows. A change to claiming, locking or the scope transaction is proven only by the
  PostgreSQL tests (`ConcurrentInstancesIntegrationTest`, `PostgresJdbcJobStoreTest`,
  `PostgresJdbcScopeAtomicityTest`).
- Comments beside version pins in the build files lag behind Dependabot bumps. Check what resolves
  with `./gradlew :dispatch-api:dependencies --configuration runtimeClasspath` instead.
- Concurrency invariants the design rests on. Run the guarding tests after touching one:

  | Invariant | Guarded by |
  | --- | --- |
  | `JobRows.inExclusiveScope` is atomic across threads and processes; the JDBC scope rolls back on any `Throwable`, `Error` included | `JdbcScopeAtomicityTest` subclasses |
  | Outcome writes are fenced by worker and attempt: call the `complete`, `fail` and `deadLetter` overloads that take the attempt | `StaleAttemptOutcomeTest` |
  | Claim permits are conserved: reserve before claiming, return unused ones at once and owed ones in `finally`; claim only what there is room to run, since a claimed job is invisible to peers until its lease expires | `ClaimCapacityTest`, `WorkerPoolShutdownTest` |
  | The interrupt flag is cleared while an outcome is recorded, since JDBC on an interrupted thread fails | `InterruptedOutcomeTest` |
  | The dispatcher and sweeper survive any `Throwable` from the store and back off one poll interval | `BackgroundErrorResilienceTest` |
  | `JobQueue` starts the pool before the sweeper; Spring starts the queue on `ApplicationReadyEvent` | `JobQueueLifecycleRaceTest`, `QueueStartupTest` |
  | `Scope.matching` skips locked rows; `Scope.byId` waits for them, so an operator action answers about the real state | the skipping side by `ConcurrentInstancesIntegrationTest`; nothing tests the waiting |

## Docs

When a doc and the code disagree, the code wins and the doc has the bug (`docs/README.md`).

- Before adding a test or a store adapter, or upgrading Gradle: `CONTRIBUTING.md`.
- Before changing claiming, leases, retry backoff, graceful shutdown or schema bootstrap:
  `docs/architecture/reliability.md`.
- Before "fixing" a gap such as the missing lease heartbeat or migration tool:
  `docs/architecture/limitations.md`, which lists the deliberate limitations in fix order.
- Before reconciling submit refusing an unknown job type with execution retrying one:
  `docs/adr/0001-unknown-job-type-policy.md`. Write a new ADR in `docs/adr/` only for a choice a
  reviewer would find surprising.
- Before adding a job type: `docs/guides/writing-a-handler.md` (delivery is at-least-once, so
  handlers are idempotent).
- Before treating missing auth or a duplicate run as a bug: the Scope section of `SECURITY.md`.
- To reproduce multi-instance claiming or crash recovery by hand:
  `docs/guides/running-multiple-instances.md`.
