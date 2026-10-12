# Dead letter and retry

A handler's permanent failure sends the job straight to `DEAD`. An operator can retry a DEAD job back to `PENDING`. Any other state is refused with 409.

## Sub-features

- `dead-letter` takes a `send-email` job with no `to` to `DEAD`.
- `retry-dead` returns 200 for `POST /jobs/{id}/retry` on a DEAD job.
- `retry-conflict` returns 409 for a retry of a COMPLETED job.

## How to get to it (user POV)

- Submit a job that cannot succeed, watch `GET /jobs?status=DEAD`, then `POST /jobs/{id}/retry`.

## Driving it with verify.sh

Preconditions:

- `verify.sh launch` and `verify.sh submit` ran in this run (`retry-conflict` reuses the COMPLETED job).

- **Lifecycle.** Run `verify.sh lifecycle`. The output shows `PASS permanent failure dead-letters job`, `PASS POST /jobs/{id}/retry on a DEAD job returns 200` and `PASS retry of a COMPLETED job is refused with 409`.

## Gotchas

- A retried job without `to` dead-letters again almost at once. Read `retry.body` for the 200 result, not a later GET.
