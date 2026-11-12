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
  only `dispatch-api` knows Spring. The classpath keeps Spring and Jackson out of core, and
  `dispatch-core/src/main/java/module-info.java` requires only `org.slf4j`, so `javac` refuses
  `java.sql` (and any other JDK module) in core main code with `package java.sql is not visible`.
  Keep JDBC in `dispatch-postgres`, behind the `JobRows` seam. A new package in core that another
  module uses needs an `exports` line in the descriptor. `InMemoryJobRows` is not exported: reach
  it through `JobStore.inMemory()`.
- With the descriptor, `-Xlint:all -Werror` flags `missing-explicit-ctor` on any public class in an
  exported package. Give it an explicit constructor with a one-line Javadoc, as `QueueMetrics` has.
- A new dependency needs its case in the PR body: it has to replace hand-rolled code (PR #7 turned
  down Error Prone on this bar). No broker, queue or scheduling library in any module, and no
  Flyway before the next schema change (see the `jobs-schema.sql` gotcha).
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

- In code, docs, and commit messages, use claim, lease, sweep, dead letter, manual retry, and
  selection. Do not use dequeue, lock, reaper, dead-letter queue, revive, requeue, or filter for
  those names.
- Write docs, Javadoc and comments in plain, short sentences with no em dashes: the owner removed
  all 216 in `ffca785`.
- Commit subjects are imperative and sentence case, with no Conventional Commits prefix. The body
  explains why. A bug fix opens its body with `BUG FIX.`, lands with its regression test in the
  same commit, says the test was red before the fix, and gives the test count change
  (`325 -> 337 tests`). Docs the change touches go in the same commit.
- PR bodies follow `.github/pull_request_template.md` and say what was not verified locally and
  why.

## Gotchas

- Files that change together. Only some pairs have a test, so check the rest by hand:
  - Engine defaults: `QueueConfig.Builder` or `ExponentialBackoffRetryPolicy`,
    `dispatch-api/src/main/resources/application.yml` (its bound values override the engine's
    under Spring; `ApplicationYmlDefaultsTest` pins the two), and `docs/reference/configuration.md`
    (`ConfigurationDocTableTest` checks its key and default table against the bound
    `application.yml` keys).
  - Lifecycle: the `Transition` enum, the mermaid diagram in
    `docs/architecture/job-lifecycle.md`, and the `CHECK` list in
    `dispatch-postgres/src/main/resources/db/jobs-schema.sql`. The diagram is tested against the
    enum by `LifecycleDocTest`, which prints the block to paste, and the `CHECK` list by
    `JobSchemaTest`, which needs Docker.
  - REST responses: the expected text in `RestContractTest` and `StatsContractTest`, and
    `docs/reference/api.md`.
  - Claim order: `JobSelection.CLAIM_ORDER` and the `idx_jobs_claim` index in `jobs-schema.sql`.
    `ClaimIndexMatchesClaimOrderTest` checks them with no Docker. The index leaves out the trailing
    `ID` on purpose (`docs/architecture/limitations.md`).
  - PostgreSQL image: `docker-compose.yml`, `PostgresTestSupport.java` and
    `PostgresEndToEndTest.java`. Dependabot bumps only the compose file.
- `jobs-schema.sql` is applied idempotently at startup with no migration tool, so
  `CREATE TABLE IF NOT EXISTS` never alters an existing table. The script now carries one
  additive, idempotent change for tables created before it,
  `ALTER TABLE jobs ADD COLUMN IF NOT EXISTS lease_id UUID`, kept out of the `CREATE TABLE` so
  every table gets the column from that one statement. `JobSchema` runs it only when the catalog
  says the column is missing, since `ADD COLUMN` takes an ACCESS EXCLUSIVE lock before it checks
  `IF NOT EXISTS`. The next schema change is the point to bring in a migration tool, not a second
  `ALTER`. `JobSchema.readStatements` drops
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
  | Outcome writes are fenced by the lease id, worker and attempt: `complete`, `fail` and `deadLetter` take the `Lease` on the claimed snapshot (`job.lease()`), so there is no unfenced form. Every lease gets a fresh id, since a manual retry resets the attempt | `StaleAttemptOutcomeTest`, `ManualRetryStaleOutcomeTest` |
  | Claim permits are conserved: reserve before claiming, return unused ones at once and owed ones in `finally`; claim only what there is room to run, since a claimed job is invisible to peers until its lease expires | `ClaimCapacityTest`, `WorkerPoolShutdownTest` |
  | The interrupt flag is cleared while an outcome is recorded, since JDBC on an interrupted thread fails | `InterruptedOutcomeTest` |
  | The dispatcher and sweeper survive any `Throwable` from the store and back off one interval of their own. `BackgroundLoop` is the one home of that catch, the backoff and the stop, so a new background thread runs on it instead of its own loop or a `ScheduledExecutorService`, which drops a task that throws. The first sweep waits one maintenance interval and `QueueMaintenance.close()` does not wait for the sweeper | `BackgroundErrorResilienceTest`, `QueueMaintenanceTest`, `SweeperTimingTest` |
  | `JobQueue` starts the pool before the sweeper; Spring starts the queue on `ApplicationReadyEvent` | `JobQueueLifecycleRaceTest`, `QueueStartupTest` |
  | `Scope.matching` skips locked rows; `Scope.byId` waits for them, so an operator action answers about the real state | the skipping side by `ConcurrentInstancesIntegrationTest`; nothing tests the waiting |

## Agent skills

`.agents/skills/` holds only skills written for this repo.

- `verify-dispatch` launches its own app (dev profile, H2) on a free loopback port and drives the REST
  API: submit to COMPLETED, validation refusals, dead letter and retry, schedule and cancel. It also
  runs `:dispatch-core:test` and, with Docker, `./gradlew build`. Run
  `.agents/skills/verify-dispatch/scripts/verify.sh all` and read its exit status. Evidence goes to
  `/tmp/dispatch-verify/<run>/`.

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

## Cursor Cloud specific instructions

`.cursor/install.sh` installs Docker and warms the Gradle distribution named in
`gradle/wrapper/gradle-wrapper.properties`. It keeps a JDK 21 already on PATH. It downloads
Temurin 21 only when `java` is not 21, and it checks that archive against the checksum Adoptium
publishes for the same build. It does not set a Docker storage driver. `.cursor/start.sh` starts
`dockerd`, because this VM has no systemd. `.agents/skills/verify-dispatch/scripts/verify.sh all`
is the gate. With Docker the script prints `RESULT: PASS`. Without a Docker daemon the build step
is an honest SKIP and the script prints `RESULT: PASS with SKIP`.
