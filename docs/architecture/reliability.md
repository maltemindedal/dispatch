# Reliability mechanics

How the interesting parts work: what the queue guarantees, what it deliberately does not, and
the mechanisms behind both.

## Claiming, across several instances

The whole multi-instance story is one SQL statement, which `JdbcJobRows` renders from the
`JobSelection.CLAIMABLE` spec that `dispatch-core` owns:

```sql
SELECT ... FROM jobs
 WHERE state IN ('PENDING') AND scheduled_at <= ?
 ORDER BY priority DESC, scheduled_at, created_at, id
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

`InMemoryJobRows` reaches the same guarantee with a `ReentrantLock` held for the whole scope, and
answers the same `JobSelection` by filtering and sorting the map. Neither adapter decides what
"claimable" means or what order it comes in — that is stated once in `dispatch-core` and rendered
twice — and both are held to the same test suite (`JobStoreContract`), so "swappable
implementations" is a test result rather than a claim.

## Delivery is at-least-once

A worker can finish a job and die before recording the result, at which point the visibility
timeout hands that job to someone else. **Handlers must be idempotent.** Exactly-once delivery is
not available to a queue that talks to the outside world, and pretending otherwise moves the
bug somewhere harder to find.

What the engine *does* guarantee is that a stalled worker cannot corrupt the record: every write
of a result is conditional on still holding the lease
(`WHERE state = 'RUNNING' AND locked_by = ?`). A worker that overran its visibility timeout finds
its update rejected, counts a lost lease (`leasesLost` in `/stats`), and gets out of the way of
whoever took the job over.

## Visibility timeout

Claiming stamps `locked_until = now + visibilityTimeout`. If a worker is killed mid-job, the row
sits in `RUNNING` with a lease nobody will ever release, and the maintenance sweeper on any
instance returns it to `PENDING`. That reclaim is the entire crash-recovery story.

The reclaimed attempt still counts against the retry budget. That is deliberate: a job that
reliably kills its worker would otherwise retry forever.

Set `visibility-timeout` comfortably above your slowest handler. Too short and healthy jobs get
run twice; too long and crash recovery crawls.

## Retries and backoff

On failure the attempt counter has already been incremented (that happened at claim time), so the
only decision left is whether any budget remains. If yes: `FAILED`, with `scheduled_at` set to
`now + backoff`. If no: `DEAD`. A handler can also throw `PermanentJobFailureException` to skip
the budget entirely — a malformed payload does not get better on the fourth attempt.

Backoff is exponential with jitter:

```text
delay = min(base * multiplier^(attempt-1), maxDelay) * (1 - jitter + jitter * random[0,1))
```

`jitterFactor = 0` is pure exponential backoff, `1.0` is AWS-style full jitter, and the default
`0.5` keeps at least half the nominal delay. The jitter is load-bearing, not decoration: jobs
usually fail in batches, because the same downstream service was down for all of them. Without
jitter that batch retries in lockstep forever and every retry wave arrives at the recovering
service simultaneously.

The knobs are in the [configuration reference](../reference/configuration.md#retry-settings-dispatchretry).

## Graceful shutdown

Three beats, on `SIGTERM` or a `JobQueue.close()`:

1. Stop claiming. The dispatcher is interrupted out of its poll.
2. Let in-flight handlers finish, up to `shutdown-drain-timeout`.
3. Interrupt whatever is still running past the deadline.

Interrupted jobs are not lost — the attempt is recorded as a failure and the job goes back on the
queue under the normal retry rules. Anything that never got that far is recovered by its lease.
Under Spring, `server.shutdown: graceful` drains HTTP first, then the `JobQueue` bean is
destroyed and drains the workers. A real `SIGTERM` under load logs it plainly:

```text
GracefulShutdown : Commencing graceful shutdown. Waiting for active requests to complete
WorkerPool       : Worker pool worker-e59ff4c1 shutting down: no longer claiming,
                   draining 8 in-flight job(s), deadline PT30S
WorkerPool       : Worker pool worker-e59ff4c1 stopped (clean drain: true)
```

## Schema creation is a race

…and `IF NOT EXISTS` doesn't fix it. Worth knowing because it looks safe and isn't:
PostgreSQL's `CREATE TABLE IF NOT EXISTS` is not atomic against concurrent DDL. Two instances
starting together both find the table missing, both create it, and the loser dies at startup with
a unique violation on the `pg_type` catalog — not with anything as readable as "table already
exists".

Two replicas rolling out simultaneously is the normal case, so `JobSchema` treats already-exists
errors (a small set of SQL states, including that catalog-level unique violation) as success and
then verifies the table is actually queryable. `JobSchemaTest` reproduces the race directly: ten
rounds of twelve threads racing from an empty schema, which fails on the first round without that
handling.

## Ordering

Claiming orders by `priority DESC, scheduled_at, created_at`: higher priority first, then oldest
due time, then oldest submission. A composite index in
[`jobs-schema.sql`](../../dispatch-postgres/src/main/resources/db/jobs-schema.sql) matches that
exact ordering so claiming is an index range scan rather than a sort. Ordering is best-effort
across instances — batches, concurrency, and retries all interleave — but within one store the
claim order itself is deterministic and contract-tested.
