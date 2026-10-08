# Running multiple instances

The PostgreSQL store lets N application instances share one `jobs` table while each job runs once.
This guide sets that up locally and tests its failure modes.

## Prerequisites

Docker, and the PostgreSQL container from the repo's compose file:

```bash
docker compose up -d
```

> **Why not H2?** The dev profile's H2 accepts the same SQL but does not reproduce PostgreSQL's
> contention behaviour. See
> [the dev profile](../reference/configuration.md#the-dev-profile-local-development). Anything
> multi-instance needs PostgreSQL.

## Start two instances

Build the jar once and run it twice. Two `bootRun` invocations would contend on the same Gradle
project lock, and separate processes are closer to a real deployment:

```bash
./gradlew :dispatch-api:bootJar
JAR=dispatch-api/build/libs/dispatch-api-0.1.0-SNAPSHOT.jar

java -jar $JAR --spring.profiles.active=postgres --server.port=8080 &
java -jar $JAR --spring.profiles.active=postgres --server.port=8081 &
```

Each instance generates its own worker id at startup (`worker-` plus a random suffix). That id is
what `locked_by` shows and what the logs name. If you set `dispatch.worker-id` explicitly, keep it
unique per process. Every claim has its own lease id, so instances sharing an id cannot record over
each other's claims, but no row or log line can then tell you which instance holds a job.

## Feed them and watch the split

```bash
for i in $(seq 1 200); do
  curl -s -X POST localhost:$((8080 + i % 2))/jobs -H 'Content-Type: application/json' \
    -d '{"type":"resize-image","payload":{"n":'"$i"'}}' > /dev/null
done

curl -s localhost:8080/stats | jq '{worker: .workerId, claimed: .thisInstance.claimed, depth: .queueDepth}'
curl -s localhost:8081/stats | jq '{worker: .workerId, claimed: .thisInstance.claimed}'
```

Both instances claim a share of the work, roughly evenly on an otherwise idle machine, and the
two `claimed` counts sum to exactly 200 once `queueDepth.COMPLETED` reaches 200, because no job
ran twice. (That exactly-once property is what `ConcurrentInstancesIntegrationTest` asserts, with
300 jobs against a containerised PostgreSQL.) There is no coordinator. The claim query's
`FOR UPDATE SKIP LOCKED` provides the mutual-exclusion mechanism
([how that works](../architecture/reliability.md#claiming-across-several-instances)).

Note that `queueDepth` is identical from both instances (it reads the shared table) while
`thisInstance` differs (those counters are process-local).

## Kill one, two ways

**Gracefully.** `Ctrl-C` (or plain `kill`) one instance mid-run. It logs that it has stopped
claiming, drains its in-flight jobs within the drain timeout, and exits; the other instance
carries on. Nothing is lost or re-run.

```text
WorkerPool : Worker pool worker-e59ff4c1 shutting down: no longer claiming,
             draining 8 in-flight job(s), deadline PT30S
WorkerPool : Worker pool worker-e59ff4c1 stopped (clean drain: true)
```

**Rudely.** `kill -9` one instance instead. Its in-flight jobs are now orphaned. Their rows stay in
`RUNNING` holding a lease nobody will release. Once each lease's visibility timeout expires
(default `5m`; set `dispatch.visibility-timeout` lower, such as `30s`, if you want to watch this
without waiting), the survivor's maintenance sweeper returns them to `PENDING` and they run again
on the surviving instance. Watch `leasesReclaimed` tick up in the survivor's `/stats`.

## Pointing at a real database

The `postgres` profile reads its connection from environment variables, defaulting to the local
compose container:

```bash
export DISPATCH_DB_URL='jdbc:postgresql://db.example.com:5432/dispatch?sslmode=verify-full&sslrootcert=/etc/ssl/certs/db-ca.pem'
export DISPATCH_DB_USER=dispatch
export DISPATCH_DB_PASSWORD=...
```

Two things to know about that connection. The PostgreSQL driver's default is `sslmode=prefer`,
which tries TLS but neither verifies the server's certificate nor refuses to fall back to plain
text, so a remote database wants `verify-full` as above. (`verify-ca` and `verify-full` read the
CA certificate from `sslrootcert`, or from `~/.postgresql/root.crt`, not from the JVM truststore,
and fail if it is missing.) And the `dispatch`/`dispatch` credentials that `docker-compose.yml` and
the profile's defaults use exist only for the local demo container; never point them at a real
database.

Schema creation at startup is safe to run from several instances simultaneously. The concurrent
bootstrap race is handled deliberately
([details](../architecture/reliability.md#schema-creation-is-a-race)).

## Keep the clocks in sync

Each instance stamps and judges leases with its own clock: a claim sets
`locked_until = now + visibility-timeout` using the claimer's time, and whichever instance's
sweeper runs next compares it with its own. Run NTP (or chrony) on every host. Skew comes out of
the same margin as your slowest handler's run time: a peer whose clock is 10 seconds ahead sees a
`5m` lease as `4m50s`, and a claimer whose clock is behind delays crash recovery by the skew. The
cost is duplicate work, with `leasesReclaimed` and `leasesLost` rising in `/stats`, not corruption:
handlers must be idempotent anyway, and a result is only recorded by the worker that still holds the
lease.
