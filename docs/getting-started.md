# Getting started

This walks you from a fresh clone to a running queue, first against in-memory H2 (no Docker),
then against PostgreSQL. Budget about ten minutes.

## Prerequisites

- **A JDK.** Any recent one works: the build declares a Java 21 toolchain and the
  [foojay resolver](../settings.gradle.kts) downloads one automatically if you don't have it.
- **Docker** — only for the PostgreSQL section and the integration tests. The H2 path needs none.

## 1. Run the application

```bash
./gradlew :dispatch-api:bootRun
```

This starts Spring Boot on `http://localhost:8080` with the default `dev` profile: an in-memory
H2 database that the application creates its own schema in at startup. There is no migration step
and nothing else to set up.

You should see log lines like:

```
Job queue backed by JDBC store
Registered job handlers: [resize-image, send-email]
Job queue worker-3f2a91bc started with handlers for [resize-image, send-email]
```

Those two handlers are bundled simulators, there so the queue has something to do out of the box.

## 2. Submit a job

In a second terminal:

```bash
curl -s -X POST localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{"type":"send-email","payload":{"to":"someone@example.com","subject":"Hi"},"maxRetries":5}'
```

The response is `201 Created` with the stored job — note the `id`, `state` (`PENDING`), and
`attempt` (`0`). A `Location` header points at the new resource.

Fetch it back a moment later:

```bash
curl -s localhost:8080/jobs/<id> | jq
```

By now it has most likely reached `COMPLETED`. If it shows `FAILED` instead, you got lucky: the
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

`queueDepth` shows jobs per lifecycle state across the whole store; `thisInstance` shows what this
process has done. With a 30% simulated failure rate you will see `failedAttempts` and
`retriesScheduled` climb alongside `succeeded` — that is the retry machinery doing its job.
The fields are documented in the [API reference](reference/api.md#get-stats).

## 4. See a job dead-letter

Submit a payload the simulator treats as permanently broken — no `"to"` field:

```bash
curl -s -X POST localhost:8080/jobs -H 'Content-Type: application/json' \
  -d '{"type":"send-email","payload":{"subject":"no recipient"}}' | jq .id
```

The handler throws a permanent failure, so the job skips its remaining retries and lands in
`DEAD` — the dead-letter state — immediately. Only an operator can revive it:

```bash
curl -s 'localhost:8080/jobs?status=DEAD' | jq '.[].id'
curl -s -X POST localhost:8080/jobs/<id>/retry | jq .state   # back to PENDING, fresh budget
```

(It will dead-letter again, of course — the payload is still missing its recipient. That
distinction between transient and permanent failure is the point of the demo; see
[Writing a handler](guides/writing-a-handler.md).)

## 5. Switch to PostgreSQL

Stop the app, then:

```bash
docker compose up -d
./gradlew :dispatch-api:bootRun --args='--spring.profiles.active=postgres'
```

`docker compose` starts PostgreSQL 17 with database, user, and password all `dispatch` (see
[docker-compose.yml](../docker-compose.yml)). The application again creates its own schema at
startup. Everything from steps 2–4 works identically — same store class, same SQL — but now the
queue is durable and shareable: restart the app and unfinished jobs are still there.

## Where next

- Two processes sharing this PostgreSQL queue without double-processing:
  [Running multiple instances](guides/running-multiple-instances.md).
- Registering your own job type: [Writing a handler](guides/writing-a-handler.md).
- All the knobs you just left at their defaults: [Configuration](reference/configuration.md).
- Why it behaves the way you just observed: [Reliability mechanics](architecture/reliability.md).
