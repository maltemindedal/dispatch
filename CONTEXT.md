# Domain glossary

Terms the code and docs use with a precise meaning. When a word here fits, use it. Don't coin a
synonym.

- **Claim.** Takes exclusive, lease-bound ownership of a batch of due jobs (`JobStore.claim`).
  Exclusive across instances until the lease expires.
- **Claim budget.** The number of jobs one claim round trip is allowed to take: at least 1, at
  most `claimBatchSize`, never more than there is capacity to run.
- **Selection.** Which rows an operation wants and in what order, stated once as a
  `JobSelection` (`CLAIMABLE`, `DUE`, `EXPIRED_LEASE`) and rendered by every adapter as a
  predicate and comparator in memory, as a `WHERE` and `ORDER BY` in SQL. Adapters render a
  selection; they never decide one.
- **Rows.** The storage seam (`JobRows`). Hold these rows exclusively and write them back.
  Everything a job means lives above it in `JobStore`; below it there is only a map under a lock
  or a transaction under `FOR UPDATE`.
- **Claim capacity.** The worker pool's backpressure valve (`ClaimCapacity`). It is a fixed pool of
  permits, one per job in flight, reserved before each claim and conserved across every dispatch
  path. Invariant: `available + in-flight == concurrency`.
- **Lease.** The exclusivity window on a claimed job (`lockedUntil`/`lockedBy`). A worker may
  only record an outcome while it still holds the lease; a lost lease is counted, not fought.
- **Sweep.** The maintenance pass that promotes due SCHEDULED/FAILED jobs to PENDING and
  reclaims jobs whose lease expired.
- **Dispatch cycle.** One claim round trip. Reserve claim budget, claim that many jobs, and hand each
  to a virtual thread (`WorkerPool.dispatchOnce`). The dispatcher thread runs cycles in a loop; a
  caller can run one by hand instead and be told what it claimed. Never both at once.
- **Dead letter.** A job whose retry budget is exhausted (state DEAD). It is revivable only by an
  explicit manual retry.
- **Refusal.** The engine declining an operator action (cancel, manual retry) with the reason
  and the state it observed, decided in the same atomic step (`JobActionResult.WrongState` /
  `NotFound`). Callers phrase refusals; they never re-check the rule.
