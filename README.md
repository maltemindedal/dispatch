# dispatch

A job queue built from scratch on `java.util.concurrent` and PostgreSQL row locks. It uses no
RabbitMQ, Kafka, Redis, or queue library. Workers run on Java 21 virtual threads. Spring Boot
appears only at the HTTP edge; the engine itself is plain Java that runs in a `main` method.

The point is to understand how a job is claimed exactly once by one of several processes, what a
visibility timeout does, why retry backoff needs jitter, and what "graceful shutdown" means for
work that is already in flight.

## Quick start

Needs a JDK. Gradle downloads a Java 21 toolchain automatically if you don't have one. The
PostgreSQL profile and integration tests also need Docker.

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

Seeing `FAILED` on a fresh job is expected. The job is waiting out a retry backoff. Set
`dispatch.demo-handlers: false` to drop the simulators in a real deployment.

With PostgreSQL:

```bash
docker compose up -d
./gradlew :dispatch-api:bootRun --args='--spring.profiles.active=postgres'
```

The application creates its own schema at startup, so there is no migration step.

## Documentation

- **Tutorial:** [Getting started](docs/getting-started.md), from zero to a running queue.
- **Guides:** [writing a handler](docs/guides/writing-a-handler.md),
  [running multiple instances](docs/guides/running-multiple-instances.md).
- **Reference:** [configuration](docs/reference/configuration.md),
  [REST API](docs/reference/api.md).
- **Architecture:** [overview](docs/architecture/overview.md), the
  [job lifecycle](docs/architecture/job-lifecycle.md),
  [reliability mechanics](docs/architecture/reliability.md), and
  [known limitations](docs/architecture/limitations.md).

The annotated index lists every document, what it covers, and who it's for:
[docs/README.md](docs/README.md).

## Project structure

```text
dispatch-core/       The engine: domain model, state machine, worker pool, and JobStore,
                     which owns every storage rule over a narrow JobRows seam.
                     Depends only on the JDK and SLF4J. It has no Spring or JDBC dependency.
dispatch-postgres/   JdbcJobRows: one adapter at that seam, backing the same engine with
                     PostgreSQL or H2 via SELECT ... FOR UPDATE SKIP LOCKED. Still no Spring.
dispatch-api/        Spring Boot: REST controllers, configuration properties, profile wiring.
docs/                Documentation (see above).
```

The dependency arrow only points inward. The build enforces that `dispatch-core` cannot see Spring
or JDBC.

## Contributing

See [docs/contributing.md](docs/contributing.md) for the build, the test suite, and what CI runs.

## License

[MIT](LICENSE)
