# Schedule and cancel

A job with a future `scheduledAt` waits as `SCHEDULED`. `DELETE` cancels a job that has not started, by deleting its row.

## Sub-features

- `schedule` returns `state` `SCHEDULED` for a future `scheduledAt`.
- `cancel` returns 204 for `DELETE /jobs/{id}`, and a later `GET` returns 404.

## How to get to it (user POV)

- Submit with `"scheduledAt":"2099-01-01T00:00:00Z"`, then `curl -X DELETE localhost:8080/jobs/<id>`.

## Driving it with verify.sh

Preconditions:

- `verify.sh launch` printed `PASS app ready`.

- **Lifecycle.** Run `verify.sh lifecycle`. The output shows `PASS future scheduledAt gives SCHEDULED`, `PASS DELETE of a SCHEDULED job returns 204` and `PASS the cancelled job is gone (404)`.

## Gotchas

- There is no CANCELLED state. A 404 after cancelling is the proof.
