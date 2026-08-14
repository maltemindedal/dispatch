# ADR-0001: Unknown job types are refused at submission, tolerated at execution

Date: 2026-08-14
Status: accepted

## Context

Two moments can meet a job type with no registered handler, and they used to hold contradictory
beliefs in different modules:

- **Submission.** `JobController` pre-checked the registry and returned 422 — "a typo is a
  client error" — using two pass-through methods on `JobQueue` that existed for no other caller.
- **Execution.** `WorkerPool` treats a missing handler as a retryable failure — "possibly a
  rolling deploy where another instance already has the handler."

Neither module owned the policy, so nothing stopped them disagreeing.

## Decision

Both behaviours are kept, because they answer different questions, and both now live in the
engine:

- **`JobQueue.submit` refuses a type this instance has no handler for** by throwing
  `UnknownJobTypeException`. A submission is a conversation with a specific instance; if that
  instance has never heard of the type, the overwhelmingly likely cause is a typo, and failing
  fast beats letting the job retry its way to the dead-letter state. The HTTP layer maps the
  exception to 422 without knowing the rule.
- **`WorkerPool` keeps retrying a claimed job whose handler is missing here.** A job in the
  shared store may have been submitted by a peer that does have the handler — mid-rolling-deploy
  this is routine — so the attempt fails and the normal retry rules apply, until the budget runs
  out and the job dead-letters with "No handler registered" as its last error.

## Consequences

- A cluster whose instances register *different* handler sets must submit each type through an
  instance that handles it. That deployment shape is not currently supported on purpose; if it
  ever is, the submission-time guard is the single place to revisit.
- Future architecture reviews should not flag the submit-refuse / execute-retry pair as a
  contradiction: it is one policy about two different moments, owned by `JobQueue.submit` and
  `WorkerPool.recordFailure` respectively, and both sides reference this ADR.
