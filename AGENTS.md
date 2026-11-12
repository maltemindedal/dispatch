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
- `./gradlew classes testClasses`: compiles every source set, which also runs the `dispatch-lint`
  rules. The fastest loop, about a second when warm, and no Docker.
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
- `dispatch-lint` uses only the JDK and no other module. It is build-time only: a javac plugin on
  the annotation processor path of the other three.
- A new dependency needs its case in the PR body: it has to replace hand-rolled code (PR #7 turned
  down Error Prone on this bar). No broker, queue or scheduling library in any module, and no
  Flyway before the next schema change (see the `jobs-schema.sql` gotcha).
- `.github/dependabot.yml` has no `cooldown` blocks on purpose, so Dependabot's default delay
  applies. PR #15 removed a self-invented 7-day rule. The `dependabot-cooldown` rule in `checkRepo`
  fails the build on a `cooldown` key there.

### Engine code

- Storage rules live once, in `JobStore` and `JobSelection`. `InMemoryJobRows` and `JdbcJobRows`
  only render a selection and write rows back. A new predicate or sort key goes in `JobSelection`
  (every order ends in the `ID` tiebreak), with its test in `JobStoreContract` so all three stores
  run it. Per-adapter copies had drifted apart before PR #6.
- Engine code reads time only from the injected `Clock`; `JobStore` methods take `now`. The
  `wall-clock` rule in `dispatch-lint` fails `Instant.now()`, `System.currentTimeMillis()` and
  `Clock.systemUTC()` in `dispatch-core` main. A constructor may default a `Clock` with
  `Clock.systemUTC()`, as `JobQueue` does.
- Guard shared state with `ReentrantLock` or `Semaphore`. Handlers run on virtual threads, and on
  this Java 21 toolchain `synchronized` pins them (JDK 24 fixed it). The `no-synchronized` rule in
  `dispatch-lint` makes a `synchronized` method or block in main code a compile error.
- Return refusals as values: `JobActionResult` (`Done`, `NotFound`, `WrongState`) for operator
  actions, `Optional.empty()` for a lost lease. Storage failures surface as `JobStoreException`.
- Add API, a state transition or a config key in the same change as the code path that uses it.
  Review has removed unused surface (`85fe8e0`) and speculative transitions (PR #11).
- Nothing publishes this code, so migrate the callers and delete the old API in the same change.
  The `no-deprecated` rule in `dispatch-lint` makes `@Deprecated` in main code a compile error.
  (`0f09dac` deprecated three unfenced overloads, and `80457fc` deleted them.)
- Reuse the existing helper (`registry.require`, `JobState.isCancellable`) instead of writing its
  body again: a repeat review finding.
- Import types instead of writing them fully qualified inline. The `inline-fqn` rule in
  `dispatch-lint` makes one a compile error that names the import, in `package-info.java` and
  `module-info.java` too. A qualified name stays legal only where the simple name already means
  another type. The rule asks javac what the simple name resolves to at that position.
- Spring Boot 4, Jackson 3 and Testcontainers 2 may postdate your training. Jackson lives in
  `tools.jackson.*` (`StringNode` replaced `TextNode`), Boot's test support is split into modules
  such as `spring-boot-resttestclient`, and the container class is
  `org.testcontainers.postgresql.PostgreSQLContainer`. Copy imports from an existing class in the
  module rather than from memory; versions are in `gradle/libs.versions.toml`.

### Repo rules in the build

- `dispatch-lint` holds every rule the build enforces, in one table, the `Rule` enum. Its error
  starts with the rule id in brackets and names the fix.
- A per-file Java rule is one `Rule` constant plus one visit method in `JavaRules`, with a test
  class like `InlineFqnTest`: a bad fixture, a good one, and one for each exemption. A whole-repo
  rule goes in `RepoCheck`, which the `:dispatch-lint:checkRepo` task runs as part of `check`.
  The text rules (a word or character in a tracked text file) live in `TextRules`, with one test
  class per rule. The task's inputs list every text file, so a text-only edit reruns it. The
  `template-residue` rule fails a bracketed NOTE, INSERT or DESCRIBE placeholder, or a TODO
  marked as a placeholder, left in a `.md` or `.yml` file, as the code of conduct once had.
- A rule lands with the fix for every violation it finds. There is no baseline file and no
  suppression comment: an exemption lives in the rule's code, with a test.
- The plugin cannot check `dispatch-lint` itself. Every other module gets it from the root
  `build.gradle.kts`, and each compile task writes an index of the sources the plugin saw. The
  `lint-ran` rule in `checkRepo` fails for a source file that no index names with its current
  content, so wiring that is lost cannot pass as a clean build. The index is
  `dispatch-lint.idx` in the task's generated sources directory, which every compile empties
  first. A compile without the plugin therefore leaves no index, even in a tree that was green.
- `dispatch-lint/replay.sh <checkout>` runs the per-file rules over another checkout, such as the
  parent of a past fix commit, to show a rule catches the mistake it is for. It also runs the text
  rules there, which need a git working tree.

### Tests

- Every test class and method carries a `@DisplayName` sentence. The `display-name` rule in
  `dispatch-lint` fails a `@Test`, `@ParameterizedTest` or `@RepeatedTest` method, and a concrete
  class that declares or inherits one, without it. A `name =` on `@ParameterizedTest` is extra, not
  a replacement. Abstract bases and classes without tests are exempt.
- Assert with AssertJ. The `junit-assert` rule in `dispatch-lint` makes a call to
  `org.junit.jupiter.api.Assertions` a compile error that names `assertThat` and
  `assertThatThrownBy`.
- Behaviour every store shares goes in `JobStoreContract` (`dispatch-core/src/testFixtures`).
  JDBC-only behaviour gets an abstract base with `H2*` and `Postgres*` subclasses, as
  `JdbcScopeAtomicityTest` does.
- Test what the engine does through `JobQueue.dispatchOnce()` and the claim it returns, and pass
  time by advancing `MutableClock`. Virtual-thread start order says nothing about claim order:
  reading it that way caused both CI flakes so far. The `order-from-timing` rule in
  `dispatch-lint` fails a test method that calls `JobQueue.start()` and then makes an ordered
  AssertJ assertion (`containsExactly`, `startsWith` and the like) on a value that did not come from
  `dispatchOnce()`. It cannot see an order raced through a helper, so that stays a review point.
  Keep a running dispatcher and Awaitility for tests about how the engine runs.

### Prose, commits and PRs

- In code, docs, and commit messages, use claim, lease, sweep, dead letter, manual retry, and
  selection. The `vocabulary` rule in `checkRepo` holds the list of words to avoid for those
  names, and fails the build on one in any tracked text file, identifiers included. Its error says
  what to write instead. Using "lock" or "filter" for them stays a review point, since
  `ReentrantLock`, `JobFilter` and `locked_until` are real names.
- Write docs, Javadoc and comments in plain, short sentences with no em dashes: the owner removed
  all 216 in `ffca785`. The `em-dash` rule in `checkRepo` fails the build on U+2014 in any tracked or
  untracked-but-not-ignored `.md`, `.java`, `.yml`, `.yaml`, `.kts`, `.sql`, `.sh`, `.toml` or
  `.properties` file. A text file that is not UTF-8 fails the `not-utf8` rule.
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
  | The dispatcher and sweeper survive any `Throwable` from the store and back off one poll interval | `BackgroundErrorResilienceTest` |
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
