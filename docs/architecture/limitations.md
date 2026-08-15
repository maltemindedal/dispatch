# Known limitations

An honest list of what this doesn't do, roughly in the order it should be fixed.

1. **No lease heartbeat.** A handler that outruns its visibility timeout gets its job
   re-delivered while it's still working. The fix is a periodic `locked_until` extension from the
   running handler. Today the mitigation is "set the timeout high enough", which is a real
   limitation, not a design choice.
2. **`InMemoryJobRows` scans and sorts the whole map to answer a selection.** O(n log n) per
   claim. It renders the same `JobSelection` the SQL `ORDER BY` does, deliberately, so both
   adapters behave identically — but a priority index would be the first optimisation if it were
   ever used for more than tests and demos.
3. **Completed jobs are kept forever.** There's no reaper. A real deployment wants
   `DELETE FROM jobs WHERE state = 'COMPLETED' AND updated_at < now() - interval '7 days'` on a
   schedule, or partitioning by month.
4. **Schema is applied by an idempotent DDL script**, not a migration tool. Fine for one schema
   version; swap in Flyway the moment there's a second.
5. **`payload` is `TEXT`, not `jsonb`.** Portable to H2, but it gives up indexing and querying
   inside payloads on PostgreSQL.
6. **The claim index is a portable composite** `(state, priority DESC, scheduled_at, created_at)`.
   On PostgreSQL alone, a partial index `WHERE state = 'PENDING'` would be strictly better — it
   keeps every completed job out of the index entirely. H2 has no partial indexes.
7. **No authentication on the API**, and `GET /jobs` has no cursor pagination, so deep `offset`
   paging degrades.
8. **`GET /stats` counters are per-process** and reset on restart. Cluster-wide throughput
   numbers would need to come from the database or a metrics backend.
