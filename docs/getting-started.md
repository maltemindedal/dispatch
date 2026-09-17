# Getting started

This guide takes you from a fresh clone to a running queue. You will use in-memory H2 first, then
PostgreSQL. Allow about ten minutes.

## Prerequisites

- **A JDK.** Any recent one works. The build declares a Java 21 toolchain, and the
  [foojay resolver](../settings.gradle.kts) downloads one automatically if you don't have it.
- **Docker**, only for the PostgreSQL section and the integration tests. The H2 path needs none.

## 1. Run the application

```bash
./gradlew :dispatch-api:bootRun
```

This starts Spring Boot on `http://localhost:8080` with the default `dev` profile. The application
uses an in-memory H2 database and creates its schema at startup. There is no migration step or
other setup.

You should see log lines like:

```text
Job queue backed by JDBC store
Registered job handlers: [resize-image, send-email]
Job queue worker-3f2a91bc started with handlers for [resize-image, send-email]
```

Those handlers are bundled simulators so the queue has work to process.

## 2. Submit a job

In a second terminal:

```bash
curl -s -X POST localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{"type":"send-email","payload":{"to":"someone@example.com","subject":"Hi"},"maxRetries":5}'
```

The response is `201 Created` with the stored job. Note the `id`, `state` (`PENDING`), and
`attempt` (`0`). A `Location` header points at the new resource.

Fetch it back a moment later:

```bash
curl -s localhost:8080/jobs/<id> | jq
```

By now it has most likely reached `COMPLETED`. If it shows `FAILED` instead, that is expected: the
`send-email` simulator fails about 30% of its attempts on purpose, and `FAILED` means the job is
waiting out a retry backoff. Fetch again after a second or two and watch `attempt` climb until it
completes.

## 3. Watch the queue work

Submit a burst and keep an eye on the stats endpoint:

```bash
for i in $(seq 1 50); do
  curl -s -X POST localhost:8080/jobs -H 'Content-Type: application/json' \
    -d '{"type":"send-email","payload":{"to":"user'"$i"'@example.com"}}' > /dev/null
done

curl -s localhost:8080/stats | jq
```

`queueDepth` shows jobs per lifecycle state across the whole store. `thisInstance` shows what this
process has done. With a 30% simulated failure rate, `failedAttempts` and `retriesScheduled` will
increase alongside `succeeded` as retries run.
The fields are documented in the [API reference](reference/api.md#get-stats).

## 4. See a job dead-letter

Submit a payload the simulator treats as permanently broken, with no `"to"` field:

```bash
curl -s -X POST localhost:8080/jobs -H 'Content-Type: application/json' \
  -d '{"type":"send-email","payload":{"subject":"no recipient"}}' | jq .id
```

The handler throws a permanent failure, so the job skips its remaining retries and immediately
lands in `DEAD`, the dead-letter state. Only an operator can revive it:

```bash
curl -s 'localhost:8080/jobs?status=DEAD' | jq '.[].id'
curl -s -X POST localhost:8080/jobs/<id>/retry | jq .state   # back to PENDING, fresh budget
```

It will dead-letter again because the payload still lacks a recipient. That distinction between
transient and permanent failure is the point of the demo. See
[Writing a handler](guides/writing-a-handler.md).

## 5. Switch to PostgreSQL

Stop the app, then:

```bash
docker compose up -d
./gradlew :dispatch-api:bootRun --args='--spring.profiles.active=postgres'
```

`docker compose` starts PostgreSQL 17 with database, user, and password all `dispatch` (see
[docker-compose.yml](../docker-compose.yml)). The application again creates its own schema at
startup. Everything from steps 2–4 works the same way with the same store class and SQL. The queue
now survives restarts, and multiple app instances can share it.

## Where next

- Two processes sharing this PostgreSQL queue without double-processing:
  [Running multiple instances](guides/running-multiple-instances.md).
- Registering your own job type: [Writing a handler](guides/writing-a-handler.md).
- All the knobs you left at their defaults: [Configuration](reference/configuration.md).
- Why it behaves the way you observed: [Reliability mechanics](architecture/reliability.md).
