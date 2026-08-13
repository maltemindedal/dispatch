# Documentation index

Everything here is grounded in the code; when a document and the code disagree, the code wins and
the document has a bug — please report it.

## Tutorial — learning by doing

| Document | What it covers | For whom |
| --- | --- | --- |
| [Getting started](getting-started.md) | Clone to a running queue: start the app, submit jobs, watch retries, switch to PostgreSQL. | Newcomers to the project. |

## How-to guides — one task per page

| Document | What it covers | For whom |
| --- | --- | --- |
| [Writing a handler](guides/writing-a-handler.md) | Registering a job type, idempotency, permanent failures, payload handling. | Anyone adding a job type. |
| [Running multiple instances](guides/running-multiple-instances.md) | Two processes on one queue: exclusive claiming, crash recovery, graceful drain. | Anyone exploring the multi-instance behaviour. |

## Reference — lookup, exhaustive and factual

| Document | What it covers | For whom |
| --- | --- | --- |
| [Configuration](reference/configuration.md) | Every `dispatch.*` key with type, default, and effect; profiles; environment variables; datasource tuning. | Operators and integrators. |
| [REST API](reference/api.md) | All endpoints, request and response shapes, validation rules, status codes, problem responses. | API clients. |

## Explanation — how it works and why

| Document | What it covers | For whom |
| --- | --- | --- |
| [Overview](architecture/overview.md) | Module layout, dependency rule, threading model, component data flow. | Anyone reading the code. |
| [Job lifecycle](architecture/job-lifecycle.md) | The six-state machine, every legal transition, and the reasoning behind the state set. | Anyone reasoning about job behaviour. |
| [Reliability mechanics](architecture/reliability.md) | `FOR UPDATE SKIP LOCKED` claiming, visibility leases, at-least-once delivery, backoff with jitter, graceful shutdown, the schema-creation race. | Anyone who wants the guarantees precisely. |
| [Known limitations](architecture/limitations.md) | What the queue deliberately does not do, roughly in the order it should be fixed. | Anyone considering extending it. |

## Contributing

| Document | What it covers | For whom |
| --- | --- | --- |
| [Contributing](contributing.md) | Build and test commands, test-suite architecture, CI, module dependency rules. | Contributors. |
