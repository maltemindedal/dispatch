# dispatch verification map

The maintained source for verifying dispatch's user-facing behavior. Read this index first.

## Baseline preconditions

- JDK 21 on `PATH`, `curl`, `python3`. For `build`, a Docker daemon.
- `verify.sh doctor` exits 0.
- Drive only an app this run launched (dev profile, fresh H2).

## Driving conventions

- Run every step through `.agents/skills/verify-dispatch/scripts/verify.sh`.
- Job types available in the dev app are `resize-image` (always succeeds) and `send-email` (30% transient failures, permanent failure without `to`).

## Proof and skip reporting

- Proof is the HTTP status, the JSON body, and a follow-up GET of the job.
- Report a SKIP as unverified. Without Docker, the PostgreSQL claim path is unverified.

## Feature entry contract

Each feature file has an H1, a paragraph, then `Sub-features`, `How to get to it (user POV)`, `Driving it with verify.sh`, `Gotchas`.

## Features

- [Build and tests](./build-and-tests.md) covers `:dispatch-core:test` and `./gradlew build`.
- [Submit and run a job](./submit-job.md) covers submission, validation, execution and stats.
- [Dead letter and retry](./dead-letter-retry.md) covers permanent failure and manual retry.
- [Schedule and cancel](./schedule-cancel.md) covers future jobs and DELETE.
