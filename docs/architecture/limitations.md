# Known limitations

This is a list of what the system does not do, in roughly the order to fix it.

1. **No lease heartbeat.** A handler that outruns its visibility timeout gets its job
   re-delivered while it's still working. The fix is a periodic `locked_until` extension from the
   running handler. Today the mitigation is "set the timeout high enough", which is a real
   limitation, not a design choice. (What it can no longer do is corrupt the record: the overrunning
   attempt's late result is rejected as a lost lease, even when the same instance re-claimed the
   job. The job can still run twice, so handlers must be idempotent.)
2. **`InMemoryJobRows` scans and sorts the whole map to answer a selection.** O(n log n) per
   claim. It renders the same `JobSelection` as the SQL `ORDER BY`, so both adapters behave
   identically. A priority index would be the first optimisation if it were
   ever used for more than tests and demos.
3. **Completed jobs are kept forever.** There's no reaper. A real deployment wants
   `DELETE FROM jobs WHERE state = 'COMPLETED' AND updated_at < now() - interval '7 days'` on a
   schedule, or partitioning by month. It also bounds the cost of `GET /stats`, which counts every
   retained row (tens of milliseconds at a million rows) on every call.
4. **Schema is applied by an idempotent DDL script**, not a migration tool. Fine for one schema
   version; swap in Flyway the moment there's a second.
5. **`payload` is `TEXT`, not `jsonb`.** Portable to H2, but it gives up indexing and querying
   inside payloads on PostgreSQL.
6. **The claim index is a portable composite** `(state, priority DESC, scheduled_at, created_at)`.
   On PostgreSQL alone, a partial index `WHERE state = 'PENDING'` would be strictly better. It
   keeps every completed job out of the index entirely. H2 has no partial indexes.

   One consequence to know about: PostgreSQL chooses the claim plan from table statistics, and
   right after a large batch of jobs becomes `PENDING` at once (a mass retry, a burst of scheduled
   jobs coming due) its estimate can say "no pending rows", so it sorts the whole backlog for each
   claim (tens of milliseconds instead of a fraction of one) until autovacuum next analyzes the
   table. A deployment that sees such bursts should make that happen sooner:

   ```sql
   ALTER TABLE jobs SET (autovacuum_analyze_scale_factor = 0.01, autovacuum_analyze_threshold = 500);
   ```

   and keep `dispatch.maintenance-batch-size` modest so a promotion is not one huge step.
7. **No authentication on the API**, and `GET /jobs` has no cursor pagination, so deep `offset`
   paging degrades. Every endpoint, including cancel and retry, is open to anyone who can reach the
   port; payloads and `lastError` are readable and can carry personal data; and the server
   listens on all interfaces. Run it on a trusted network or behind a reverse proxy that
   authenticates. The `dev` profile can also serve the H2 web console at `/h2-console`, but only
   when started with `DISPATCH_H2_CONSOLE=true`; it is meant for local use only, so leave it off
   (and use the `postgres` profile) for anything that is not on your own machine. `POST /jobs` bodies are capped
   (`dispatch.max-payload-bytes`, 1 MiB), but `GET /jobs` is not bounded by payload size: it returns
   up to 1000 rows with their full payloads, so a deployment that stores large payloads should ask
   for a small `limit`.
8. **`GET /stats` counters are per-process** and reset on restart. Cluster-wide throughput
   numbers would need to come from the database or a metrics backend.
