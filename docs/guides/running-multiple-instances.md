# Running multiple instances

The whole point of the PostgreSQL store is that N application instances can share one `jobs`
table and every job still runs exactly once. This guide sets that up locally and pokes at the
failure modes.

## Prerequisites

Docker, and the PostgreSQL container from the repo's compose file:

```bash
docker compose up -d
```

> **Why not H2?** The dev profile's in-memory H2 accepts the same SQL but has coarser locking:
> a second instance pointed at the same H2 database gets empty claims rather than the next
> unlocked rows. Single instance is fine on H2; anything multi-instance needs PostgreSQL.

## Start two instances

Build the jar once and run it twice — two `bootRun` invocations would contend on the same Gradle
project lock, and separate processes are closer to the real deployment anyway:

```bash
./gradlew :dispatch-api:bootJar
JAR=dispatch-api/build/libs/dispatch-api-0.1.0-SNAPSHOT.jar

java -jar $JAR --spring.profiles.active=postgres --server.port=8080 &
java -jar $JAR --spring.profiles.active=postgres --server.port=8081 &
```

Each instance generates its own worker id at startup (`worker-` plus a random suffix). That id is
the lease key — if you ever set `dispatch.worker-id` explicitly, it must be unique per process, or
instances can release each other's leases.

## Feed them and watch the split

```bash
for i in $(seq 1 200); do
  curl -s -X POST localhost:$((8080 + i % 2))/jobs -H 'Content-Type: application/json' \
    -d '{"type":"resize-image","payload":{"n":'"$i"'}}' > /dev/null
done

curl -s localhost:8080/stats | jq '{worker: .workerId, claimed: .thisInstance.claimed, depth: .queueDepth}'
curl -s localhost:8081/stats | jq '{worker: .workerId, claimed: .thisInstance.claimed}'
```

A representative run: instance A claimed 104, instance B claimed 96, `COMPLETED` is 200. The two
counts add up exactly, because no job ran twice. There is no coordinator making that happen — the
claim query's `FOR UPDATE SKIP LOCKED` is the entire mutual-exclusion mechanism
([how that works](../architecture/reliability.md#claiming-across-several-instances)).

Note that `queueDepth` is identical from both instances (it reads the shared table) while
`thisInstance` differs (those counters are process-local).

## Kill one, two ways

**Gracefully.** `Ctrl-C` (or plain `kill`) one instance mid-run. It logs that it has stopped
claiming, drains its in-flight jobs within the drain timeout, and exits; the other instance
carries on. Nothing is lost or re-run.

```
WorkerPool : Worker pool worker-e59ff4c1 shutting down: no longer claiming,
             draining 8 in-flight job(s), deadline PT30S
WorkerPool : Worker pool worker-e59ff4c1 stopped (clean drain: true)
```

**Rudely.** `kill -9` one instance instead. Its in-flight jobs are now orphaned: rows stuck in
`RUNNING` holding a lease nobody will release. Once each lease's visibility timeout expires
(default `5m` — set `dispatch.visibility-timeout` lower, e.g. `30s`, if you want to watch this
without waiting), the survivor's maintenance sweeper returns them to `PENDING` and they run again
on the surviving instance. Watch `leasesReclaimed` tick up in the survivor's `/stats`.

## Pointing at a real database

The `postgres` profile reads its connection from environment variables, defaulting to the local
compose container:

```bash
export DISPATCH_DB_URL=jdbc:postgresql://db.example.com:5432/dispatch
export DISPATCH_DB_USER=dispatch
export DISPATCH_DB_PASSWORD=...
```

Schema creation at startup is safe to run from several instances simultaneously — the concurrent
bootstrap race is handled deliberately
([details](../architecture/reliability.md#schema-creation-is-a-race)).
