# Reliability mechanics

This section explains what the queue guarantees, what it does not, and
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
instances queue up behind each other. Instance B would *block* on the rows instance A holds.
`SKIP LOCKED` changes that to "pretend those rows aren't there", so B walks past A's rows and
takes the next ones down the ordering.

That is the entire mutual-exclusion mechanism. No leader election, no distributed lock, no
partitioning of work between instances, no coordinator to fail over. N application instances can
use the same table, and each row still goes to exactly one of them. The claim and the `UPDATE`
that marks rows `RUNNING` share a transaction, so a crash in between rolls back and the jobs stay
`PENDING`.

`InMemoryJobRows` reaches the same guarantee with a `ReentrantLock` held for the whole scope, and
answers the same `JobSelection` by filtering and sorting the map. Neither adapter decides what
"claimable" means or what order it uses. `dispatch-core` states that once, and both adapters render
it. The shared test suite (`JobStoreContract`) verifies that they implement the same contract.

## Delivery is at-least-once

A worker can finish a job and die before recording the result, at which point the visibility
timeout hands that job to someone else. **Handlers must be idempotent.** Exactly-once delivery is
not available to a queue that talks to the outside world, and pretending otherwise moves the
bug somewhere harder to find.

What the engine *does* guarantee is that a stalled worker cannot corrupt the record: every write
of a result is conditional on still holding the lease *it was handed* (the row is `RUNNING`,
`lease_id` is the id of that lease, `locked_by` is this worker, and `attempt` is the attempt number
this worker was handed). Every lease gets a fresh id when a claim takes the job, so no two leases
share one. The claim hands the worker that lease as a value, `Lease`, and `JobStore` records an
outcome only in exchange for one, so no write can skip the check.
A worker that overran its visibility timeout finds its update rejected, counts a lost lease
(`leasesLost` in `/stats`), and gets out of the way of whoever took the job over. That includes
itself: if the same instance reclaims and re-runs the job as attempt 2, attempt 1's late result is
rejected too, rather than landing on attempt 2.

The attempt number alone would not be enough. A manual retry resets it, so after a stalled last
attempt is dead-lettered and an operator retries the job, the next claim can be the same worker's
attempt 1 again. Only the lease id tells the two leases apart, and it is what stops the stalled
attempt from completing the job, or dead-lettering it again, under the new claim. The worker and
attempt checks stay as well, for rolling deploys: an instance that predates lease ids claims
without writing `lease_id`, so a row it holds can still carry an earlier lease's id. For the same
reason, while such an instance still runs, two instances that share a worker id can still record
over each other's leases after a manual retry.

## Visibility timeout

Claiming stamps `locked_until = now + visibilityTimeout`. If a worker is killed mid-job, the row
sits in `RUNNING` with a lease nobody will ever release, and the maintenance sweeper on any
instance returns it to `PENDING`. That reclaim is the entire crash-recovery story.

The reclaimed attempt still counts against the retry budget. That is deliberate: a job that
reliably kills its worker would otherwise retry forever. So when the attempt that was lost was the
last one the budget allowed (`maxRetries + 1` attempts in all), the sweeper dead-letters the job
(`DEAD`, with an explanation in `lastError`) instead of returning it to `PENDING`. The same holds
for a handler that simply outruns the visibility timeout on its last attempt: with no heartbeat
(see [limitations](limitations.md)), the engine cannot tell it from a crash.

Set `visibility-timeout` comfortably above your slowest handler. Too short and healthy jobs get
run twice; too long and crash recovery crawls.

## Retries and backoff

On failure the attempt counter has already been incremented (that happened at claim time), so the
only decision left is whether any budget remains. If yes: `FAILED`, with `scheduled_at` set to
`now + backoff`. If no: `DEAD`. `JobStore.fail` makes that decision on the row it holds under the
lease, in the same step as the write, and asks the retry policy for a backoff only when a retry is
left, so a policy never has to answer for the attempt after the last. The rule lives in
`Job.attemptFailed`, next to
`Job.reclaimed`, which makes the same decision for an expired lease. A handler can also throw
`PermanentJobFailureException` to skip the budget entirely. A malformed payload does not get better
on the fourth attempt.

Backoff is exponential with jitter:

```text
delay = min(base * multiplier^(attempt-1), maxDelay) * (1 - jitter + jitter * random[0,1))
```

`jitterFactor = 0` is pure exponential backoff, `1.0` is AWS-style full jitter, and the default
`0.5` keeps at least half the nominal delay. Jitter affects recovery time. Jobs usually fail in
batches because the same downstream service was down for all of them. Without
jitter that batch retries in lockstep forever and every retry wave arrives at the recovering
service simultaneously.

The knobs are in the [configuration reference](../reference/configuration.md#retry-settings-dispatchretry).

## Graceful shutdown

The shutdown sequence on `SIGTERM` or `JobQueue.close()` has three steps:

1. Stop claiming. The dispatcher is interrupted out of its poll.
2. Let in-flight handlers finish, up to `shutdown-drain-timeout`.
3. Interrupt whatever is still running past the deadline.

Interrupted jobs are not lost. The attempt is recorded as a failure and the job goes back on the
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

`IF NOT EXISTS` does not fix the race. It looks safe, but it is not:
PostgreSQL's `CREATE TABLE IF NOT EXISTS` is not atomic against concurrent DDL. Two instances
starting together both find the table missing, both create it, and the loser dies at startup with
a unique violation on the `pg_type` catalog, not with anything as readable as "table already
exists".

Two replicas rolling out simultaneously is the normal case, so `JobSchema` treats already-exists
errors (a small set of SQL states, including that catalog-level unique violation) as success and
then verifies the table has every column `JdbcJobRows` uses. `JobSchemaTest` reproduces the race
directly: ten rounds of twelve threads racing from an empty schema, which fails on the first round
without that handling.

The script also carries one additive change, `ALTER TABLE jobs ADD COLUMN IF NOT EXISTS lease_id`,
for tables created before the column existed. `ADD COLUMN` takes an ACCESS EXCLUSIVE lock before it
checks `IF NOT EXISTS`, so `JobSchema` looks the column up in the catalog first, which locks
nothing, and runs the statement only when the column is missing. A normal startup therefore never
waits behind an open transaction on `jobs`, such as a backup. Two instances that both find the
column missing are safe too: PostgreSQL checks again under the lock, so the second waits, then
skips. `JobSchemaTest` covers both: twelve instances upgrading one table at once, and a restart
with a one-second `lock_timeout` while a reader holds the table open.

## Ordering

Claiming orders by `priority DESC, scheduled_at, created_at`: higher priority first, then oldest
due time, then oldest submission. A composite index in
[`jobs-schema.sql`](../../dispatch-postgres/src/main/resources/db/jobs-schema.sql) matches that
exact ordering so claiming is an index range scan rather than a sort. Ordering is best-effort
across instances. Batches, concurrency, and retries all interleave, but within one store the
claim order itself is deterministic and contract-tested.
