---
name: verify-dispatch
description: Drive dispatch (durable Java job queue with a Spring Boot REST edge) the way an operator with curl does and capture proof. It runs the engine tests and the full Gradle build, launches its own app on a free loopback port, submits jobs, and follows them to COMPLETED and DEAD. It also covers retry, scheduling and cancellation. Use before claiming a dispatch change works.
---

# Verify dispatch

Users touch dispatch through its REST API (`docs/reference/api.md`). Contributors touch `./gradlew build`. Everything goes through one helper:

```sh
.agents/skills/verify-dispatch/scripts/verify.sh <doctor|core-test|build|launch|submit|lifecycle|stop|cleanup|all>
```

Each step prints `PASS`/`FAIL`/`SKIP` lines and ends with `RESULT: PASS`, `RESULT: PASS with SKIP` or `RESULT: FAIL`. The exit code is 0 unless something failed. A SKIP still means that step is unverified.

## Launch

`verify.sh launch` runs `./gradlew :dispatch-api:bootJar`. It starts the jar with `--server.port=<free port> --server.address=127.0.0.1` on the default `dev` profile, which uses in-memory H2 and needs no Docker. The app is ready when `app.log` shows `Started JobQueueApplication` and `GET /stats` returns 200. The PID goes in `app.pid` and the port in `app.port`. Every launch has a fresh in-memory database, so runs never share state. Teardown is `verify.sh stop` (SIGTERM, which drives graceful shutdown).

The `postgres` profile (`docker compose up -d`) is not driven. The compose file publishes the fixed port 5432 with a named volume, so it is a shared instance. Claim contention is proven only by the PostgreSQL Testcontainers tests that `build` runs.

## Doctor

`verify.sh doctor` is read-only. It requires JDK 21 or newer on `PATH`, plus `curl` and `python3`. It warns when no Docker daemon answers `docker info`, and reports whether this run's app is up.

## Drive

- `core-test` runs `./gradlew :dispatch-core:test`, the engine tests, which need no Docker.
- `build` runs `./gradlew build`, CI's whole gate: `-Xlint:all -Werror` and every test, the Testcontainers ones included. It SKIPs without a Docker daemon, because those tests fail rather than skip without it.
- `submit` checks a single job end to end:
  - `POST /jobs` with `resize-image` returns 201, and polling `GET /jobs/{id}` sees `state` reach `COMPLETED`.
  - An unknown type returns 422.
  - A body with no `type` returns 400.
  - `GET /stats` counts the success in `thisInstance.succeeded`.
- `lifecycle` checks state changes:
  - `send-email` with no `to` fails permanently and reaches `DEAD`.
  - `POST /jobs/{id}/retry` on it returns 200. On the COMPLETED job it returns 409.
  - A future `scheduledAt` gives `SCHEDULED`.
  - `DELETE` on that job returns 204, and a `GET` afterwards returns 404.
- `all` runs doctor, core-test, build, launch, submit, lifecycle and cleanup. It stops after doctor if doctor fails.

Feature recipes are in [`features/README.md`](features/README.md).

## Evidence

Artifacts go to `$DISPATCH_VERIFY_ARTIFACTS`, default `/tmp/dispatch-verify/<run-id>`. Set `DISPATCH_VERIFY_RUN=<id>` to keep several steps together. Each request writes `<name>.cmd`, `<name>.stdout` (the HTTP status), `<name>.body` (the JSON), `.stderr` and `.exit`. The app log is `app.log`. A state claim is proven by a later `GET /jobs/{id}`, not by the submit response.

## Cleanup

`verify.sh cleanup` stops only the PID in this run's `app.pid`, never anything by name. It keeps every artifact. Gradle's `build/` output is gitignored and left in place.
