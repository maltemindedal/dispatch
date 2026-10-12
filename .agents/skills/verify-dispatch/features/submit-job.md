# Submit and run a job

An operator submits a job over REST. The dispatcher claims it, a handler runs it, and the job ends `COMPLETED`. Bad submissions are refused up front.

## Sub-features

- `submit-ok` returns 201 for `POST /jobs`, and the job reaches `COMPLETED`.
- `submit-unknown-type` returns 422 for an unknown type.
- `submit-invalid` returns 400 for a missing `type`.
- `stats` shows the success in `GET /stats` under `thisInstance.succeeded`.

## How to get to it (user POV)

- Run `curl -X POST localhost:8080/jobs -H 'Content-Type: application/json' -d '{"type":"resize-image"}'`, then `curl localhost:8080/jobs/<id>`.

## Driving it with verify.sh

Preconditions:

- `verify.sh launch` printed `PASS app ready`.

- **Submit.** Run `verify.sh submit`. Every check PASSes, including `job <id> was claimed and reached COMPLETED`.
- **Proof.** `submit.body`, `submit-get.body` (final state) and `stats.body`.

## Gotchas

- The job field is `state`, not `status`, even though the list filter's query parameter is `status`.
