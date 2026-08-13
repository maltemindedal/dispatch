# Writing a handler

A handler is a `JobHandler` — one method, `handle(JobContext)` — paired with the job type it
serves. This guide covers registering one under Spring and getting its semantics right.

## Register the handler

Declare a `JobHandlerRegistration` bean; `QueueConfiguration` collects every such bean at startup
and registers it with the engine:

```java
@Bean
JobHandlerRegistration generateReport(ReportService reports) {
    return new JobHandlerRegistration("generate-report", context -> {
        // context.payload() is the raw JSON string, exactly as submitted
        reports.generate(context.payload());
    });
}
```

That is the whole integration surface. Handlers stay plain `JobHandler` lambdas with no Spring in
them — the registration record is what keeps the framework out of `dispatch-core`.

Submissions for a type with no registered handler are rejected at the API with `422`, listing the
types that do exist, rather than being allowed to retry their way to the dead-letter state.

## The contract

- **Return normally to succeed; throw anything to fail the attempt.** A failed attempt is retried
  with backoff until the retry budget (`maxRetries`, default 3) runs out, then dead-lettered.
- **Throw `PermanentJobFailureException` to skip retries entirely.** A malformed payload does not
  get better on the fourth attempt; send it straight to `DEAD`.
- **Be idempotent.** Delivery is at-least-once: a worker can finish your handler and die before
  recording the result, and the job will run again elsewhere. Design so that running twice is
  harmless — see [Reliability mechanics](../architecture/reliability.md#delivery-is-at-least-once).
- **Blocking I/O is fine.** Each job runs on its own virtual thread; blocking calls are cheap and
  expected. Don't reach for reactive wrappers on the handler's account.
- **Finish inside the visibility timeout** (`5m` by default). A handler that outruns its lease
  gets its job re-delivered to another worker while it is still running, and its own result is
  then rejected. If your slowest handler needs longer, raise
  [`dispatch.visibility-timeout`](../reference/configuration.md).

## What the context gives you

| Method | Meaning |
| --- | --- |
| `payload()` | The JSON document from the submission, verbatim, as a `String`. The engine never parses it — use whatever JSON library your handler prefers, or keep it opaque. |
| `jobId()`, `type()`, `job()` | Identity and, if you need it, the full job snapshot. |
| `attempt()` | 1-based: `1` on the first run, `2` on the first retry. |
| `maxRetries()`, `isFinalAttempt()` | The budget, and whether a failure here dead-letters instead of retrying. |
| `workerId()` | Which instance is running this attempt. |
| `leaseExpiresAt()`, `leaseExpired(now)` | The visibility deadline. Long-running handlers should check this and bail out rather than plough on past their lease. |
| `isCancelled()` | Cooperative cancellation — true once graceful shutdown has interrupted this thread. Poll it in tight non-blocking loops; blocking calls get the interrupt directly. |

Errors thrown by a handler are recorded on the job (`lastError`, truncated to 2000 characters) —
full stack traces belong in your logs.

## The bundled simulators

Two demo handlers ship in `dispatch-core` and register themselves by default:

- **`send-email`** fails ~30% of attempts with a simulated transient error, and fails
  *permanently* if the payload has no `"to"` field — the transient-versus-fatal distinction the
  retry machinery exists to draw.
- **`resize-image`** takes a variable amount of time, useful for watching in-flight counts and
  drain behaviour.

Turn them off in anything real:

```yaml
dispatch:
  demo-handlers: false
```
