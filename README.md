# dispatch

A job queue built from scratch on `java.util.concurrent` and PostgreSQL row locks — no RabbitMQ,
no Kafka, no Redis, no queue library of any kind. Workers run on Java 21 virtual threads. Spring
Boot appears only at the HTTP edge; the engine itself is plain Java that runs fine in a `main`
method.

The point is to understand the mechanics: how a job is claimed exactly once by one of several
processes, what a visibility timeout actually buys you, why retry backoff needs jitter, and what
"graceful shutdown" has to mean for work that is already in flight.

---

## Quick start

Needs a JDK (any recent one — Gradle downloads a Java 21 toolchain automatically if you don't
have one) and, for the PostgreSQL profile and the integration tests, Docker.

```bash
# In-memory H2, no Docker needed. Serves on http://localhost:8080
./gradlew :dispatch-api:bootRun

# Everything: unit tests, both store implementations, Testcontainers integration tests
./gradlew build
```

Submit a job and watch it run:

```bash
curl -s -X POST localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{"type":"send-email","payload":{"to":"someone@example.com","subject":"Hi"},"maxRetries":5}'

# The email simulator fails about 30% of the time, so this is worth watching:
curl -s localhost:8080/stats | jq
```

With PostgreSQL:

```bash
docker compose up -d
./gradlew :dispatch-api:bootRun --args='--spring.profiles.active=postgres'
```

The application creates its own schema at startup, so there is no migration step.

---

## Layout

| Module | Depends on | What lives there |
| --- | --- | --- |
| `dispatch-core` | JDK + SLF4J | The engine. Domain model, state machine, handler registry, retry policy, `JobStore` interface, in-memory store, virtual-thread worker pool, maintenance sweeper. No Spring, no JDBC. |
| `dispatch-postgres` | `dispatch-core` | `JdbcJobStore` — the same engine backed by PostgreSQL or H2, using `SELECT ... FOR UPDATE SKIP LOCKED`. Still no Spring. |
| `dispatch-api` | both | Spring Boot: REST controllers, configuration properties, profile wiring. |

The dependency arrow only ever points inward. `dispatch-core` cannot see Spring or JDBC, which is
enforced by the build rather than by good intentions.

---

## The job lifecycle

Six states, with every legal transition declared explicitly in `JobState` and validated on every
change:

```
  submit(now)      ┌──────────────┐  claim   ┌─────────┐  success   ┌───────────┐
  ────────────────▶│   PENDING    │─────────▶│ RUNNING │───────────▶│ COMPLETED │
                   └──────────────┘          └─────────┘            └───────────┘
                     ▲     ▲   ▲                 │  │
  submit(future)     │     │   │ visibility      │  │ failure, retries left
  ┌───────────┐ due  │     │   │ timeout         │  ▼
  │ SCHEDULED │──────┘     │   └─────────────────┤ ┌────────┐  backoff elapsed
  └───────────┘            │                     │ │ FAILED │────────────────────┐
                           │                     │ └────────┘                    │
                           │  manual retry       │  │ retries exhausted          │
                           │  ┌──────┐           │  ▼                            │
                           └──│ DEAD │◀──────────┴─────                          │
                              └──────┘                                           │
                           ▲                                                     │
                           └─────────────────────────────────────────────────────┘
```

Three distinctions worth internalising, because they are what the six states are *for*:

- **`SCHEDULED` vs `FAILED`.** Both hold a future `scheduled_at` and both are invisible to
  claiming until it passes. `SCHEDULED` means "delayed, never attempted"; `FAILED` means
  "attempted, waiting out a backoff". Collapsing them would make `GET /stats` unable to
  distinguish a healthy backlog from a retry storm.
- **`FAILED` vs `DEAD`.** `FAILED` is transient — a sweeper will promote it. `DEAD` is the
  dead-letter state, and only an operator (`POST /jobs/{id}/retry`) gets a job out of it.
- **There is no `CANCELLED`.** Cancelling a job that never ran deletes the row. A state whose only
  meaning is "this never happened" is a row you keep forever for no reason.

`COMPLETED` is a one-way door. Re-running finished work is a new job, not a resurrection.

---

## How the interesting parts work

### Claiming, across several instances

The whole multi-instance story is one SQL statement (`JdbcJobStore`):

```sql
SELECT ... FROM jobs
 WHERE state = 'PENDING' AND scheduled_at <= ?
 ORDER BY priority DESC, scheduled_at, created_at
 LIMIT ?
 FOR UPDATE SKIP LOCKED
```

`FOR UPDATE` locks the returned rows until the transaction commits. On its own that would make
instances queue up behind each other — instance B would *block* on the rows instance A holds.
`SKIP LOCKED` changes that to "pretend those rows aren't there", so B walks past A's rows and
takes the next ones down the ordering.

That is the entire mutual-exclusion mechanism. No leader election, no distributed lock, no
partitioning of work between instances, no coordinator to fail over. N application instances can
hammer the same table and every row still goes to exactly one of them. The claim and the `UPDATE`
that marks rows `RUNNING` share a transaction, so a crash in between rolls back and the jobs stay
`PENDING`.

`InMemoryJobStore` reaches the same guarantee with a `ReentrantLock` around a map scan. Both are
held to the same test suite (`JobStoreContract`), so "swappable implementations" is a test result
rather than a claim.

### Delivery is at-least-once

A worker can finish a job and die before recording the result, at which point the visibility
timeout hands that job to someone else. **Handlers must be idempotent.** Exactly-once delivery is
not available to a queue that talks to the outside world, and pretending otherwise just moves the
bug somewhere harder to find.

What the engine *does* guarantee is that a stalled worker cannot corrupt the record: every write
of a result is conditional on still holding the lease
(`WHERE state = 'RUNNING' AND locked_by = ?`). A worker that overran its visibility timeout finds
its update rejected, counts a lost lease, and gets out of the way of whoever took the job over.

### Retries and backoff

On failure the attempt counter has already been incremented (that happened at claim time), so the
decision is simply whether any budget is left. If yes: `FAILED`, with `scheduled_at` set to
`now + backoff`. If no: `DEAD`. A handler can also throw `PermanentJobFailureException` to skip
the budget entirely — a malformed payload does not get better on the fourth attempt.

Backoff is exponential with jitter:

```
delay = min(base * multiplier^(attempt-1), maxDelay) * (1 - jitter + jitter * random[0,1))
```

`jitterFactor = 0` is pure exponential backoff, `1.0` is AWS-style full jitter, and the default
`0.5` keeps at least half the nominal delay. The jitter is load-bearing, not decoration: jobs
usually fail in batches, because the same downstream service was down for all of them. Without
jitter that batch retries in lockstep forever and every retry wave arrives at the recovering
service simultaneously.

### Visibility timeout

Claiming stamps `locked_until = now + visibilityTimeout`. If a worker is killed mid-job, the row
sits in `RUNNING` with a lease nobody will ever release, and the maintenance sweeper on any
instance returns it to `PENDING`.

The reclaimed attempt still counts against the retry budget. That is deliberate: a job that
reliably kills its worker would otherwise retry forever.

Set `visibility-timeout` comfortably above your slowest handler. Too short and healthy jobs get
run twice; too long and crash recovery crawls.

### Graceful shutdown

Three beats, on `SIGTERM` or a `JobQueue.close()`:

1. Stop claiming. The dispatcher is interrupted out of its poll.
2. Let in-flight handlers finish, up to `shutdown-drain-timeout`.
3. Interrupt whatever is still running past the deadline.

Interrupted jobs are not lost — the attempt is recorded as a failure and the job goes back on the
queue under the normal retry rules. Anything that never got that far is recovered by its lease.
Under Spring, `server.shutdown: graceful` drains HTTP first, then the `JobQueue` bean is destroyed
and drains the workers. A real `SIGTERM` under load logs it plainly:

```
GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete
WorkerPool       : Worker pool worker-e59ff4c1 shutting down: no longer claiming,
                   draining 8 in-flight job(s), deadline PT30S
WorkerPool       : Worker pool worker-e59ff4c1 stopped (clean drain: true)
```

### Schema creation is a race, and `IF NOT EXISTS` doesn't fix it

Worth knowing because it looks safe and isn't: PostgreSQL's `CREATE TABLE IF NOT EXISTS` is not
atomic against concurrent DDL. Two instances starting together both find the table missing, both
create it, and the loser dies at startup with a unique violation on the `pg_type` catalog — not
with anything as readable as "table already exists".

Two replicas rolling out simultaneously is the normal case, so `JobSchema` treats
already-exists errors as success and then verifies the table is actually queryable.
`JobSchemaTest` reproduces the race directly: ten rounds of twelve threads racing from an empty
schema, which fails on the first round without that handling.

### Why virtual threads, and where they aren't used

Handlers run one per virtual thread: they are numerous, short-lived and mostly blocked on I/O, so
ten thousand concurrent jobs cost ten thousand cheap stacks instead of ten thousand OS threads.
Blocking calls inside a handler are fine and expected.

The dispatcher is a *platform* thread. There is exactly one, it lives for the whole process, and
it spends its life blocked in a database call — none of the reasons to prefer a virtual thread
apply. Using one everywhere just because they exist is how you end up not knowing what they're
for.

A `Semaphore` sized to `concurrency` gates claiming, so the engine never claims work it has no
room to run. This matters more than it looks: a claimed job is invisible to every other instance
until its lease expires, so a greedy instance parks work it can't start while its idle peers
starve.

---

## REST API

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/jobs` | Submit. `201` with a `Location` header; `422` if no handler is registered for the type. |
| `GET` | `/jobs/{id}` | `200` or `404`. |
| `GET` | `/jobs?status=&type=&limit=&offset=` | Newest first. `limit` defaults to 100, max 1000. |
| `POST` | `/jobs/{id}/retry` | Revive a `DEAD` job with a fresh retry budget. `404` unknown, `409` if not `DEAD`. |
| `DELETE` | `/jobs/{id}` | Cancel a job that hasn't started. `204`; `409` once it is `RUNNING` or finished. |
| `GET` | `/stats` | Queue depth per state plus this instance's counters. |

Errors come back as RFC 9457 problem documents.

```bash
# Submit, with priority and a delay
curl -X POST localhost:8080/jobs -H 'Content-Type: application/json' -d '{
  "type": "resize-image",
  "payload": {"url": "s3://bucket/cat.png", "width": 800},
  "priority": 10,
  "maxRetries": 3,
  "scheduledAt": "2026-12-24T09:00:00Z"
}'

curl localhost:8080/jobs/<id>
curl 'localhost:8080/jobs?status=DEAD&limit=20'
curl -X POST localhost:8080/jobs/<id>/retry
curl -X DELETE localhost:8080/jobs/<id>
curl -s localhost:8080/stats | jq
```

`GET /stats` splits cluster-wide facts from process-local ones, because mixing them misleads:

```json
{
  "workerId": "worker-3f2a91bc",
  "queueDepth": { "PENDING": 12, "SCHEDULED": 3, "RUNNING": 4,
                  "COMPLETED": 981, "FAILED": 2, "DEAD": 7 },
  "totalJobs": 1009,
  "backlog": 17,
  "thisInstance": {
    "submitted": 1009, "claimed": 604, "succeeded": 573,
    "failedAttempts": 31, "retriesScheduled": 24, "deadLettered": 7,
    "leasesReclaimed": 0, "leasesLost": 0, "inFlight": 4,
    "failureRate": 0.0513, "averageExecutionMs": 143.7
  }
}
```

`queueDepth` comes from the shared database and describes the whole cluster. `thisInstance` counts
what this process has done since it started and resets on restart. `failureRate` is per *attempt*,
not per job: a job that fails twice and then succeeds contributes two failures and one success.

---

## Writing a handler

```java
@Bean
JobHandlerRegistration generateReport(ReportService reports) {
    return new JobHandlerRegistration("generate-report", context -> {
        // context.payload() is the raw JSON string, exactly as submitted
        reports.generate(context.payload());
    });
}
```

- Return normally to succeed; throw anything to fail the attempt.
- Throw `PermanentJobFailureException` to skip retries and dead-letter immediately.
- Be idempotent (see at-least-once, above).
- Blocking I/O is fine — you're on a virtual thread.

Two simulators ship in `dispatch-core`: `send-email` fails ~30% of the time (and *permanently* if the
payload has no `to` field, which is the transient-versus-fatal distinction the retry machinery
exists to draw), and `resize-image` takes a variable amount of time. Turn them off with
`dispatch.demo-handlers=false`.

---

## Configuration

All under `dispatch.*` (see `application.yml` for the annotated defaults):

| Key | Default | Notes |
| --- | --- | --- |
| `store` | `jdbc` | `jdbc` or `memory`. `memory` is process-local and lost on restart. |
| `worker-id` | generated | Must be unique per process; two instances sharing an id can release each other's leases. |
| `concurrency` | `16` | Max jobs in flight per instance. |
| `claim-batch-size` | `8` | Jobs per claim round trip. |
| `poll-interval` | `250ms` | Idle poll. Local submissions wake the dispatcher early, so this only bounds latency for work created elsewhere. |
| `visibility-timeout` | `5m` | Must exceed your slowest handler. |
| `maintenance-interval` | `1s` | How often to promote due jobs and reclaim leases. |
| `shutdown-drain-timeout` | `30s` | How long shutdown waits before interrupting. |
| `retry.base-delay` / `.multiplier` / `.max-delay` / `.jitter-factor` | `1s` / `2.0` / `1m` / `0.5` | Backoff curve. |
| `demo-handlers` | `true` | Register the bundled simulators. |

Profiles: `dev` (default, H2 in memory) and `postgres` (reads `DISPATCH_DB_URL`, `DISPATCH_DB_USER`,
`DISPATCH_DB_PASSWORD`).

---

## Seeing two instances share a queue

Build the jar once and run it twice — two `bootRun` invocations would contend on the same Gradle
project lock, and separate processes are closer to the real deployment anyway:

```bash
docker compose up -d
./gradlew :dispatch-api:bootJar
JAR=dispatch-api/build/libs/dispatch-api-0.1.0-SNAPSHOT.jar

java -jar $JAR --spring.profiles.active=postgres --server.port=8080 &
java -jar $JAR --spring.profiles.active=postgres --server.port=8081 &

for i in $(seq 1 200); do
  curl -s -X POST localhost:$((8080 + i % 2))/jobs -H 'Content-Type: application/json' \
    -d '{"type":"resize-image","payload":{"n":'"$i"'}}' > /dev/null
done

curl -s localhost:8080/stats | jq '{worker: .workerId, claimed: .thisInstance.claimed, depth: .queueDepth}'
curl -s localhost:8081/stats | jq '{worker: .workerId, claimed: .thisInstance.claimed}'
```

A representative run: instance A claimed 104, instance B claimed 96, `COMPLETED` is 200. The two
counts add up exactly, because no job ran twice.

Kill one with `Ctrl-C` mid-run and watch it drain its in-flight work before exiting while the
other carries on. Kill one with `kill -9` instead and watch the survivor's sweeper reclaim the
orphaned jobs once their visibility leases expire.

---

## Tests

```bash
./gradlew test                      # everything
./gradlew :dispatch-core:test          # engine only, no Docker
```

180 tests, all green, no Docker needed for `:dispatch-core:test`. The ones that carry weight:

- **`JobStoreContract`** — one suite, run against all three stores (in-memory, H2, PostgreSQL).
  Every store must claim exclusively, order by priority then age, reject writes from a worker that
  lost its lease, and reclaim expired leases. This is what makes the implementations genuinely
  interchangeable.
- **`ConcurrentInstancesIntegrationTest`** — two instances with separate connection pools against
  one containerised PostgreSQL, 300 jobs, asserting every job executed exactly once, that both
  instances did work, and that a job orphaned by a "crashed" instance is recovered by its peer.
- **`RetryAndDeadLetterTest`**, **`VisibilityTimeoutTest`**, **`ScheduledJobTest`** — driven by a
  `MutableClock`, so "wait out the backoff" is an assignment rather than a `Thread.sleep`. Fast and
  not flaky.
- **`GracefulShutdownTest`** — uses real wall-clock time, because draining is the one thing where
  actual elapsed time is the behaviour under test.
- **`JobSchemaTest`** — races twelve threads through schema creation ten times over, which is the
  regression test for the startup bug described above.

---

## Known limits, and what I'd do next

Honest list of what this doesn't do, roughly in the order I'd fix them:

1. **No lease heartbeat.** A handler that outruns its visibility timeout gets its job re-delivered
   while it's still working. The fix is a periodic `locked_until` extension from the running
   handler. Today the mitigation is "set the timeout high enough", which is a real limitation, not
   a design choice.
2. **`InMemoryJobStore.claim` scans and sorts the whole map.** O(n log n) per claim. It mirrors the
   SQL `ORDER BY` deliberately so both stores behave identically, but a priority index would be the
   first optimisation if it were ever used for more than tests and demos.
3. **Completed jobs are kept forever.** There's no reaper. A real deployment wants
   `DELETE FROM jobs WHERE state = 'COMPLETED' AND updated_at < now() - interval '7 days'` on a
   schedule, or partitioning by month.
4. **Schema is applied by an idempotent DDL script**, not a migration tool. Fine for one schema
   version; swap in Flyway the moment there's a second.
5. **`payload` is `TEXT`, not `jsonb`.** Portable to H2, but it gives up indexing and querying
   inside payloads on PostgreSQL.
6. **The claim index is a portable composite** `(state, priority DESC, scheduled_at, created_at)`.
   On PostgreSQL alone, a partial index `WHERE state = 'PENDING'` would be strictly better —
   it keeps every completed job out of the index entirely. H2 has no partial indexes.
7. **No authentication on the API**, and `GET /jobs` has no cursor pagination, so deep `offset`
   paging degrades.
8. **`GET /stats` counters are per-process** and reset on restart. Cluster-wide throughput numbers
   would need to come from the database or a metrics backend.
