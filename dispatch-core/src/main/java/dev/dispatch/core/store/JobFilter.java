package dev.dispatch.core.store;

import dev.dispatch.core.job.JobState;

/**
 * Query for {@link JobStore#list}. Null state or type means "any".
 *
 * @param state  restrict to one lifecycle state, or null
 * @param type   restrict to one job type, or null
 * @param limit  maximum rows to return
 * @param offset rows to skip, for paging
 */
public record JobFilter(JobState state, String type, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    public JobFilter {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be within [1, " + MAX_LIMIT + "]: " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
    }

    public static JobFilter all() {
        return new JobFilter(null, null, DEFAULT_LIMIT, 0);
    }

    public static JobFilter byState(JobState state) {
        return new JobFilter(state, null, DEFAULT_LIMIT, 0);
    }

    public static JobFilter byType(String type) {
        return new JobFilter(null, type, DEFAULT_LIMIT, 0);
    }

    public JobFilter withLimit(int newLimit) {
        return new JobFilter(state, type, newLimit, offset);
    }

    public JobFilter withOffset(int newOffset) {
        return new JobFilter(state, type, limit, newOffset);
    }
}
