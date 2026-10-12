# Build and tests

Contributors prove a change with the engine tests (fast, no Docker) and the full Gradle build, which is CI's only step.

## Sub-features

- `core-test` runs `./gradlew :dispatch-core:test`, which passes.
- `build` runs `./gradlew build`, which passes, including the Testcontainers PostgreSQL tests.

## How to get to it (user POV)

- Run `./gradlew :dispatch-core:test` or `./gradlew build` at the repo root.

## Driving it with verify.sh

Preconditions:

- `doctor` passes. `build` also needs a Docker daemon.

- **Core.** Run `verify.sh core-test`. The output shows `PASS dispatch-core tests pass` and `PASS gradle reports BUILD SUCCESSFUL`.
- **Build.** Run `verify.sh build`. The output shows `PASS ./gradlew build passes`, or `SKIP` without Docker.

## Gotchas

- The build cache replays unchanged test tasks. Add `--rerun-tasks` by hand to force a real run.
- In a log, find failures with `> Task .* FAILED`. Test names contain `FAILED` on green runs.
