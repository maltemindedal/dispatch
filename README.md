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

Submit a job and watch it run:

```bash
curl -s -X POST localhost:8080/jobs \
  -H 'Content-Type: application/json' \
  -d '{"type":"send-email","payload":{"to":"someone@example.com","subject":"Hi"},"maxRetries":5}'

# The email simulator fails about 30% of the time, so this is worth watching:
curl -s localhost:8080/stats | jq
```

With PostgreSQL:

```bash
docker compose up -d
./gradlew :dispatch-api:bootRun --args='--spring.profiles.active=postgres'
```

The application creates its own schema at startup, so there is no migration step.

## Documentation

| | |
| --- | --- |
| [Getting started](docs/getting-started.md) | Zero to a running queue, step by step. |
| [Writing a handler](docs/guides/writing-a-handler.md) | Register your own job type and get the semantics right. |
| [Running multiple instances](docs/guides/running-multiple-instances.md) | See two processes share one queue without double-processing. |
| [Configuration reference](docs/reference/configuration.md) | Every `dispatch.*` key, profile, and environment variable. |
| [REST API reference](docs/reference/api.md) | Endpoints, request/response shapes, status codes, errors. |
| [Architecture](docs/architecture/overview.md) | Modules, threading model, and how the pieces fit. |
| [Job lifecycle](docs/architecture/job-lifecycle.md) | The six states and why they are these six. |
| [Reliability mechanics](docs/architecture/reliability.md) | Claiming, leases, at-least-once delivery, backoff, shutdown. |
| [Known limitations](docs/architecture/limitations.md) | What this deliberately doesn't do, and what to fix first. |
| [Contributing](docs/contributing.md) | Build, test, and CI details for working on the code. |

The full index lives at [docs/README.md](docs/README.md).

## Project structure

```
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
