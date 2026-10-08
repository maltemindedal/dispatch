-- Job queue schema. Written to be valid on both PostgreSQL and H2, so the same store class
-- serves the production database and the local dev profile.
--
-- Applied idempotently at startup by JobSchema. A real deployment would hand this to Flyway or
-- Liquibase instead and version the migrations; the mechanism changes, the DDL does not.

CREATE TABLE IF NOT EXISTS jobs (
    id           UUID         PRIMARY KEY,
    type         VARCHAR(255) NOT NULL,
    payload      TEXT,
    priority     INTEGER      NOT NULL DEFAULT 0,
    max_retries  INTEGER      NOT NULL DEFAULT 3,
    attempt      INTEGER      NOT NULL DEFAULT 0,
    state        VARCHAR(16)  NOT NULL,
    scheduled_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    locked_until TIMESTAMP(6) WITH TIME ZONE,
    locked_by    VARCHAR(255),
    -- lease_id is deliberately missing here. The ALTER TABLE below adds it.
    last_error   TEXT,
    -- The lifecycle is enforced in the domain model; this keeps the database honest too, so a
    -- stray hand-written UPDATE cannot invent a seventh state.
    CONSTRAINT jobs_state_check CHECK (
        state IN ('PENDING', 'SCHEDULED', 'RUNNING', 'COMPLETED', 'FAILED', 'DEAD')
    ),
    CONSTRAINT jobs_attempt_check CHECK (attempt >= 0 AND max_retries >= 0)
);

-- The one change since the first schema version, and it only adds. lease_id is the id of a RUNNING
-- job's lease, drawn fresh each time a claim takes the job. This version sets it at claim and
-- clears it when the lease ends. An instance that predates it does not write it at all.
--
-- CREATE TABLE IF NOT EXISTS never alters a table that is already there, so this statement adds
-- the column to a table created before it existed and does nothing to one that has it. The column
-- is kept out of the CREATE TABLE above on purpose. Every table, new or old, then gets it from
-- this one statement, so the two cannot drift apart, and a new database runs the same statement
-- an old one does.
--
-- JobSchema runs it only when the catalog says the column is missing. ADD COLUMN takes an ACCESS
-- EXCLUSIVE lock before it looks at IF NOT EXISTS, so running it on every startup would queue
-- behind any open transaction on jobs and stall every claim behind that.
--
-- Two instances running it at once is safe. PostgreSQL checks for the column under the table
-- lock, so the second waits for the first, then finds the column and skips.
--
-- The next schema change is the point to bring in a migration tool, not a second ALTER here.
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS lease_id UUID;

-- Backs the claim query: state first (equality), then the exact ORDER BY the claim uses, so
-- claiming is an index range scan rather than a sort over the whole table.
--
-- On PostgreSQL alone this is better as a partial index.
--   CREATE INDEX ... ON jobs (priority DESC, scheduled_at, created_at) WHERE state = 'PENDING'
-- which keeps completed jobs out of the index entirely. H2 has no partial indexes, so the
-- portable composite form is used here.
CREATE INDEX IF NOT EXISTS idx_jobs_claim ON jobs (state, priority DESC, scheduled_at, created_at);

-- Backs the two maintenance sweeps: expiring leases and promoting jobs that have come due.
CREATE INDEX IF NOT EXISTS idx_jobs_lease ON jobs (state, locked_until);
CREATE INDEX IF NOT EXISTS idx_jobs_due ON jobs (state, scheduled_at);

-- Backs GET /jobs?type=... and the listing order.
CREATE INDEX IF NOT EXISTS idx_jobs_type ON jobs (type);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs (created_at DESC, id DESC);
