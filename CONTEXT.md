# Domain glossary

Terms the code and docs use with a precise meaning. When a word here fits, use it — don't coin a
synonym.

- **Claim** — taking exclusive, lease-bound ownership of a batch of due jobs (`JobStore.claim`).
  Exclusive across instances until the lease expires.
- **Claim budget** — the number of jobs one claim round trip is allowed to take: at least 1, at
  most `claimBatchSize`, never more than there is capacity to run.
- **Selection** — which rows an operation wants and in what order, stated once as a
  `JobSelection` (`CLAIMABLE`, `DUE`, `EXPIRED_LEASE`) and rendered by every adapter — as a
  predicate and comparator in memory, as a `WHERE` and `ORDER BY` in SQL. Adapters render a
  selection; they never decide one.
- **Rows** — the storage seam (`JobRows`): hold these rows exclusively, write these rows back.
  Everything a job means lives above it in `JobStore`; below it there is only a map under a lock
  or a transaction under `FOR UPDATE`.
- **Claim capacity** — the worker pool's backpressure valve (`ClaimCapacity`): a fixed pool of
  permits, one per job in flight, reserved before each claim and conserved across every dispatch
  path. Invariant: `available + in-flight == concurrency`.
- **Lease** — the exclusivity window on a claimed job (`lockedUntil`/`lockedBy`). A worker may
  only record an outcome while it still holds the lease; a lost lease is counted, not fought.
- **Sweep** — the maintenance pass that promotes due SCHEDULED/FAILED jobs to PENDING and
  reclaims jobs whose lease expired.
- **Dispatch cycle** — one claim round trip: reserve claim budget, claim that many jobs, hand each
  to a virtual thread (`WorkerPool.dispatchOnce`). The dispatcher thread runs cycles in a loop; a
  caller can run one by hand instead and be told what it claimed. Never both at once.
- **Dead letter** — a job whose retry budget is exhausted (state DEAD); revivable only by an
  explicit manual retry.
- **Refusal** — the engine declining an operator action (cancel, manual retry) with the reason
  and the state it observed, decided in the same atomic step (`JobActionResult.WrongState` /
  `NotFound`). Callers phrase refusals; they never re-check the rule.
