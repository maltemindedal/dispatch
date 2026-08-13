# Configuration reference

All queue settings live under `dispatch.*` and bind to `QueueProperties`
(`dispatch-api/src/main/java/dev/dispatch/api/config/QueueProperties.java`). The annotated
defaults are in [`application.yml`](../../dispatch-api/src/main/resources/application.yml).

Engine knobs left unset fall through to the engine's own defaults (`QueueConfig.Builder` for the
queue, `ExponentialBackoffRetryPolicy` for retries) — the values below are defined there, in
exactly one place, and `application.yml` restates them.

## Queue settings (`dispatch.*`)

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `store` | `jdbc` \| `memory` | `jdbc` | Which `JobStore` backs the queue. `memory` is process-local, lost on restart, and logs a warning to that effect. |
| `worker-id` | string | `""` (generate) | This instance's identity in `locked_by`. Blank means "generate a unique one at startup" (hostname-ish plus random suffix), which is what you want in containers. If set, it must be unique per process — two instances sharing an id can release each other's leases. |
| `concurrency` | int ≥ 1 | `16` | Maximum jobs in flight on this instance. Also sizes the claim-gating semaphore, so the engine never claims work it has no room to run. |
| `claim-batch-size` | int ≥ 1 | `8` | Jobs claimed per database round trip. |
| `poll-interval` | duration | `250ms` | How long an idle dispatcher parks before polling again. Submissions on this instance wake it early, so this only bounds the pickup latency of work created elsewhere. |
| `visibility-timeout` | duration | `5m` | How long a claim stays exclusive before the job is deemed abandoned. Must comfortably exceed your slowest handler, or healthy jobs get run twice. |
| `maintenance-interval` | duration | `1s` | How often the sweeper promotes due jobs and reclaims expired leases. |
| `maintenance-batch-size` | int ≥ 1 | `500` | Row cap per maintenance pass, keeping those statements short. |
| `shutdown-drain-timeout` | duration | `30s` | How long shutdown waits for in-flight jobs before interrupting them. |
| `demo-handlers` | boolean | `true` | Register the bundled `send-email` and `resize-image` simulators. Turn off in a real deployment. |

Durations use Spring's syntax: `250ms`, `30s`, `5m`.

## Retry settings (`dispatch.retry.*`)

These four keys shape the exponential-backoff-with-jitter curve described in
[Reliability mechanics](../architecture/reliability.md#retries-and-backoff), which also explains
why the jitter is not optional decoration.

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `retry.base-delay` | duration | `1s` | Delay before the first retry. |
| `retry.multiplier` | double ≥ 1.0 | `2.0` | Growth factor per attempt. |
| `retry.max-delay` | duration | `1m` | Ceiling the growth is clamped to. |
| `retry.jitter-factor` | double in [0, 1] | `0.5` | `0` is pure exponential backoff; `1.0` is AWS-style full jitter; the default keeps at least half the nominal delay. |

## Profiles

The default profile is `dev` (set via `spring.profiles.default` in `application.yml`).

### The dev profile (local development)

[`application-dev.yml`](../../dispatch-api/src/main/resources/application-dev.yml). In-memory H2
(`jdbc:h2:mem:dispatch`), no Docker required, `dev.dispatch` logging at DEBUG, and the H2 web
console enabled at `/h2-console`.

The same `JdbcJobStore` runs here as against PostgreSQL — same SQL, same claim query. What H2
does not reproduce is contention behaviour: its locking is coarser, so a second instance pointed
at the same H2 database gets empty claims rather than the next unlocked rows. Single instance is
fine; for anything multi-instance, use the `postgres` profile.

### The postgres profile

[`application-postgres.yml`](../../dispatch-api/src/main/resources/application-postgres.yml).
Connection settings come from environment variables, with defaults matching the repo's
[docker-compose.yml](../../docker-compose.yml):

| Variable | Default |
| --- | --- |
| `DISPATCH_DB_URL` | `jdbc:postgresql://localhost:5432/dispatch` |
| `DISPATCH_DB_USER` | `dispatch` |
| `DISPATCH_DB_PASSWORD` | `dispatch` |

Two tuning notes baked into that profile:

- Hikari's `maximum-pool-size` is `24` — deliberately above the default `concurrency` of 16,
  because the dispatcher, the maintenance sweeper, and every finishing handler all want a
  connection, and starving them stalls the queue. Raise `concurrency` and this needs to follow.
- Hikari's `connection-timeout` takes plain milliseconds (`10000`), not a Spring duration —
  `"10s"` fails to bind.

## Application-level settings

Also set in `application.yml`, not `dispatch.*` but load-bearing:

| Setting | Value | Why |
| --- | --- | --- |
| `server.shutdown` | `graceful` | On SIGTERM, HTTP drains first; then the `JobQueue` bean is destroyed, which stops claiming and drains workers. |
| `spring.threads.virtual.enabled` | `true` | HTTP requests run on virtual threads too, matching the worker pool. Nothing in the engine depends on this. |

## Using the engine without Spring

`dispatch-core` has no configuration files. Programmatic equivalents of everything above:
`QueueConfig.builder()` for the queue knobs, `new ExponentialBackoffRetryPolicy(...)` for the
backoff curve, both handed to `JobQueue.builder()`.
