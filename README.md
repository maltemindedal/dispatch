# dispatch

A job queue built from scratch on `java.util.concurrent` and PostgreSQL row locks — no RabbitMQ,
no Kafka, no Redis, no queue library of any kind. Workers run on Java 21 virtual threads. Spring
Boot appears only at the HTTP edge; the engine itself is plain Java that runs fine in a `main`
method.

The point is to understand the mechanics: how a job is claimed exactly once by one of several
processes, what a visibility timeout actually buys you, why retry backoff needs jitter, and what
"graceful shutdown" has to mean for work that is already in flight.

## Quick start

Needs a JDK (any recent one — Gradle downloads a Java 21 toolchain automatically if you don't
have one) and, for the PostgreSQL profile and the integration tests, Docker.

```bash
# In-memory H2, no Docker needed. Serves on http://localhost:8080
./gradlew :dispatch-api:bootRun

# Everything: unit tests, both store implementations, Testcontainers integration tests
./gradlew build
```

The app is ready when it logs
`Job queue worker-... started with handlers for [resize-image, send-email]`. Submit a job and
watch it run:

```bash
curl -s -X POST localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{"type":"send-email","payload":{"to":"someone@example.com","subject":"Hi"},"maxRetries":5}'
# → 201 with the stored job: {"id":"...","state":"PENDING","attempt":0,...}

# The bundled send-email handler is a simulator that fails ~30% of attempts *on purpose*, to
# give the retry/backoff machinery something to do. Watch attempts climb and jobs recover:
curl -s localhost:8080/stats | jq
```

Seeing `FAILED` on a fresh job is expected — it's waiting out a retry backoff, not broken. Set
`dispatch.demo-handlers: false` to drop the simulators in a real deployment.

With PostgreSQL:

```bash
docker compose up -d
./gradlew :dispatch-api:bootRun --args='--spring.profiles.active=postgres'
```

The application creates its own schema at startup, so there is no migration step.

## Documentation

- **Tutorial** — [Getting started](docs/getting-started.md): zero to a running queue.
- **Guides** — [writing a handler](docs/guides/writing-a-handler.md),
  [running multiple instances](docs/guides/running-multiple-instances.md).
- **Reference** — [configuration](docs/reference/configuration.md),
  [REST API](docs/reference/api.md).
- **Architecture** — [overview](docs/architecture/overview.md), the
  [job lifecycle](docs/architecture/job-lifecycle.md),
  [reliability mechanics](docs/architecture/reliability.md), and
  [known limitations](docs/architecture/limitations.md).

The annotated index — every document, what it covers, who it's for — is
[docs/README.md](docs/README.md).

## Project structure

```text
dispatch-core/       The engine: domain model, state machine, worker pool, in-memory store.
                     Depends on the JDK and SLF4J only — no Spring, no JDBC.
dispatch-postgres/   JdbcJobStore: the same engine backed by PostgreSQL or H2 via
                     SELECT ... FOR UPDATE SKIP LOCKED. Still no Spring.
dispatch-api/        Spring Boot: REST controllers, configuration properties, profile wiring.
docs/                Documentation (see above).
```

The dependency arrow only ever points inward — `dispatch-core` cannot see Spring or JDBC, which
is enforced by the build rather than by good intentions.

## Contributing

See [docs/contributing.md](docs/contributing.md) for the build, the test suite, and what CI runs.

## License

[MIT](LICENSE)
