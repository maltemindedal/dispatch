# Dispatch

A job queue in which several workers share one store of jobs and each job is held by at most one
worker at a time. These are the words the code and docs use with a precise meaning: when one fits,
use it rather than coining a synonym.

## Language

### Claiming work

**Claim**:
Exclusive, time-limited ownership of a batch of claimable jobs, taken by one worker and respected by
every instance until each job's **Lease** expires.
_Avoid_: dequeue (a claimed job stays in the store, RUNNING)

**Claim capacity**:
A worker's room to run more jobs: its concurrency less the jobs it has in flight. A worker never
claims more than its free capacity.

**Claim budget**:
The number of jobs one **Claim** may take: at least one, and no more than the free
**Claim capacity** or the configured claim batch size.

**Dispatch cycle**:
One claim round trip: reserve a **Claim budget**, make one **Claim** with it, and start every job
it took.

**Lease**:
The exclusivity window on a claimed job, held by one worker for one **Claim** of it. Every claim
gives the job a new lease, so only the attempt that claim started may record the job's outcome; a
result from any other claim, even the same worker's with the same attempt number, is a lost lease.
_Avoid_: lock (a row lock lasts one transaction; a lease outlives it)

### Recovery and failure

**Sweep**:
The periodic maintenance pass that promotes due SCHEDULED and FAILED jobs to PENDING and reclaims
jobs whose **Lease** expired.
_Avoid_: reaper (a reaper deletes finished jobs, and there is none)

**Dead letter**:
A job in the DEAD state because it exhausted its retry budget or failed permanently. Only a
**Manual retry** takes it out of that state.
_Avoid_: dead-letter queue (DEAD is a job state, not a separate queue)

**Manual retry**:
An operator sending a **Dead letter** back to PENDING with a fresh retry budget.
_Avoid_: revive, requeue (requeue also describes a reclaimed or interrupted job going back to
PENDING)

**Refusal**:
The engine declining an operator action, such as a cancel or a **Manual retry**, with the reason
and the job state it observed when it decided.

### Storage

**Selection**:
A named rule for which jobs an operation wants and in what order: claimable, due, or expired lease.
Every storage adapter applies the same selection rather than deciding its own.
_Avoid_: filter (a filter narrows a job listing by state or type)

**Rows**:
The storage seam: exclusive access to job rows, to hold them and write them back. Everything a job
means lives above it.

## Relationships

- A **Dispatch cycle** reserves a **Claim budget** out of **Claim capacity**, then spends it on one
  **Claim**
- A **Claim** gives each job it takes a **Lease**; a **Sweep** reclaims the jobs whose **Lease**
  expired
- **Claims** and **Sweeps** find their jobs through a **Selection**, applied over the **Rows**
- A **Manual retry** of a **Dead letter** either returns it to PENDING or comes back as a
  **Refusal**
