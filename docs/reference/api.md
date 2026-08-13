# REST API reference

Base URL: `http://localhost:8080` by default. All bodies are JSON. Errors come back as
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem documents (see
[Errors](#errors) below).

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/jobs` | Submit a job |
| `GET` | `/jobs/{id}` | Fetch one job |
| `GET` | `/jobs` | List jobs, newest first |
| `POST` | `/jobs/{id}/retry` | Revive a dead-lettered job |
| `DELETE` | `/jobs/{id}` | Cancel a job that has not started |
| `GET` | `/stats` | Queue depth plus this instance's counters |

---

## POST /jobs

Submits a job. Types with no registered handler are rejected here rather than being allowed to
retry their way to the dead-letter state — a typo in a job type is a client error and reads
like one.

```bash
curl -X POST localhost:8080/jobs -H 'Content-Type: application/json' -d '{
  "type": "resize-image",
  "payload": {"url": "s3://bucket/cat.png", "width": 800},
  "priority": 10,
  "maxRetries": 3,
  "scheduledAt": "2026-12-24T09:00:00Z"
}'
```

**Request body**

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `type` | string, ≤ 255 chars | yes | Handler routing key. |
| `payload` | any JSON | no | Stored verbatim and handed to the handler untouched. `null`/absent is stored as `{}`. |
| `priority` | integer | no | Higher runs first; omit for the normal band (`0`). |
| `maxRetries` | integer ≥ 0 | no | Retries beyond the first attempt. Default `3`. |
| `scheduledAt` | ISO-8601 instant | no | Run no earlier than this. Omit to run as soon as possible. A future instant submits the job as `SCHEDULED`. |

**Responses**

- `201 Created` — the [job](#the-job-resource), with a `Location: /jobs/{id}` header.
- `400 Bad Request` — validation failure; the problem document carries a per-field `errors` map.
- `422 Unprocessable Entity` — no handler registered for `type`; the message lists the types
  that exist.

## GET /jobs/{id}

- `200 OK` — the [job](#the-job-resource).
- `404 Not Found`.

## GET /jobs

Lists jobs, newest first.

```bash
curl 'localhost:8080/jobs?status=DEAD&limit=20'
```

| Query param | Default | Meaning |
| --- | --- | --- |
| `status` | any | Restrict to one lifecycle state (`PENDING`, `SCHEDULED`, `RUNNING`, `COMPLETED`, `FAILED`, `DEAD`). |
| `type` | any | Restrict to one job type. |
| `limit` | `100` | Page size, 1–1000. |
| `offset` | `0` | Rows to skip. |

Out-of-range values are a `400`. Returns a JSON array of [jobs](#the-job-resource).

## POST /jobs/{id}/retry

Requeues a dead-lettered job with a fresh retry budget.

- `200 OK` — the revived job, back in `PENDING` with `attempt` reset.
- `404 Not Found` — no such job.
- `409 Conflict` — the job exists but is not `DEAD`.

## DELETE /jobs/{id}

Cancels a job that has not started — only `PENDING` and `SCHEDULED` qualify. Cancelling deletes
the row: the lifecycle has no `CANCELLED` state, because a job nobody ran leaves nothing worth
keeping.

- `204 No Content` — cancelled (deleted).
- `404 Not Found` — no such job.
- `409 Conflict` — already running or finished.

## GET /stats

```bash
curl -s localhost:8080/stats | jq
```

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

The response splits cluster-wide facts from process-local ones, because mixing them misleads:

- **`queueDepth`**, **`totalJobs`**, **`backlog`** come from the shared store and describe the
  whole cluster right now. `backlog` is jobs still owed execution:
  `PENDING + SCHEDULED + FAILED`.
- **`thisInstance`** counts what this process has done since it started, and resets on restart.

| `thisInstance` field | Meaning |
| --- | --- |
| `submitted` | Jobs enqueued via this instance. |
| `claimed` | Jobs this instance's dispatcher claimed. |
| `succeeded` | Attempts that succeeded here. |
| `failedAttempts` | Attempts that threw here. |
| `retriesScheduled` | Failures put back on the queue with a backoff. |
| `deadLettered` | Jobs this instance moved to `DEAD`. |
| `leasesReclaimed` | Abandoned leases this instance's sweeper recovered. |
| `leasesLost` | Results that could not be recorded because the lease was gone. |
| `inFlight` | Jobs running right now. |
| `failureRate` | Failed attempts over finished attempts, in [0, 1]. Per *attempt*, not per job: a job that fails twice then succeeds contributes two failures and one success. |
| `averageExecutionMs` | Mean handler wall time. |

## The job resource

```json
{
  "id": "5f0c9a3e-...",
  "type": "send-email",
  "payload": { "to": "someone@example.com", "subject": "Hi" },
  "priority": 0,
  "maxRetries": 5,
  "attempt": 2,
  "retriesRemaining": 4,
  "state": "FAILED",
  "scheduledAt": "2026-08-14T10:15:32.417Z",
  "createdAt": "2026-08-14T10:15:30.002Z",
  "updatedAt": "2026-08-14T10:15:32.417Z",
  "lockedUntil": null,
  "lockedBy": null,
  "lastError": "SMTP server refused the message (simulated transient failure), attempt 2"
}
```

Notes:

- `payload` comes back as real JSON, not an escaped string. A stored payload that no longer
  parses (hand-edited, older version) is returned as a JSON string rather than breaking the read.
- `retriesRemaining` is computed so callers don't do the `maxRetries`/`attempt` arithmetic.
- `attempt` is 1-based once the job has run; `0` means never attempted.
- Null fields (`lockedUntil`, `lockedBy`, `lastError` on a healthy job) are omitted from the JSON
  (`non_null` serialization) — the example shows them for completeness.
- `state` is one of the six [lifecycle states](../architecture/job-lifecycle.md).

## Errors

Problem documents per RFC 9457, `Content-Type: application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Job is in the wrong state",
  "status": 409,
  "detail": "Only DEAD jobs can be retried; job 5f0c9a3e-... is RUNNING",
  "instance": "/jobs/5f0c9a3e-.../retry"
}
```

Validation failures (`400`) additionally carry an `errors` object mapping field names to
messages.
