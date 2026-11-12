package dev.dispatch.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dispatch.core.store.JobSelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The claim query sorts by {@link JobSelection#CLAIMABLE}'s ordering, and {@code idx_jobs_claim}
 * is what lets PostgreSQL read rows already in that order. Nothing ties the two together, so a new
 * sort key could ship without its index column and only slow claims down at a large backlog.
 *
 * <p>The index stops one column short of the ordering. The trailing {@code id} tiebreak is left
 * out on purpose; docs/architecture/limitations.md says why. Needs no Docker.
 */
@DisplayName("idx_jobs_claim versus the claim order")
class ClaimIndexMatchesClaimOrderTest {

    private static final Pattern CLAIM_INDEX = Pattern.compile(
            "CREATE INDEX IF NOT EXISTS idx_jobs_claim ON jobs \\(([^)]*)\\)");

    @Test
    @DisplayName("the index is state followed by the claim ordering without its id tiebreak")
    void indexColumnsFollowTheClaimOrder() throws IOException {
        String schema;
        try (InputStream in = JobSchema.class.getResourceAsStream("/db/jobs-schema.sql")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher index = CLAIM_INDEX.matcher(schema);
        assertThat(index.find())
                .as("jobs-schema.sql must create idx_jobs_claim on one line")
                .isTrue();
        int line = (int) schema.substring(0, index.start()).chars().filter(c -> c == '\n').count() + 1;

        String ordering = JdbcJobRows.orderByClause(JobSelection.CLAIMABLE.ordering())
                .substring(" ORDER BY ".length());
        String expected = "state, " + ordering.substring(0, ordering.lastIndexOf(", id"));

        assertThat(index.group(1).trim())
                .as("jobs-schema.sql line %d: change the idx_jobs_claim columns to (%s) so they"
                                + " match JobSelection.CLAIMABLE's ordering (the trailing id is"
                                + " left out on purpose). CREATE INDEX IF NOT EXISTS keeps an existing"
                                + " database's old index, so this is a schema change: see the"
                                + " jobs-schema.sql gotcha in AGENTS.md",
                        line, expected)
                .isEqualTo(expected);
    }
}
