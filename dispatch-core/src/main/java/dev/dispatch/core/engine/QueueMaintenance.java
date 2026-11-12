package dev.dispatch.core.engine;

import dev.dispatch.core.store.JobStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The background sweeper. Two jobs, both essential, both easy to overlook:
 *
 * <ol>
 *   <li><b>Promote due jobs.</b> SCHEDULED and FAILED rows carry a future {@code scheduledAt};
 *       moving them to PENDING when that time arrives is what actually makes delayed jobs run and
 *       retry backoff expire.</li>
 *   <li><b>Reclaim expired leases.</b> A worker that was killed mid-job leaves its row RUNNING
 *       forever. Returning rows whose visibility lease has lapsed to PENDING is the entire
 *       crash-recovery story, except that a job whose lapsed attempt was its last permitted one is
 *       dead-lettered instead of retried.</li>
 * </ol>
 *
 * <p>Every instance runs this against the shared store, and that is fine. The operations are
 * idempotent and atomic, so overlapping sweeps just find less to do.
 */
public final class QueueMaintenance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(QueueMaintenance.class);

    private final JobStore store;
    private final QueueConfig config;
    private final QueueMetrics metrics;
    private final Clock clock;
    private final Runnable onWorkPromoted;

    private volatile BackgroundLoop sweeper;

    public QueueMaintenance(
            JobStore store,
            QueueConfig config,
            QueueMetrics metrics,
            Clock clock,
            Runnable onWorkPromoted) {
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onWorkPromoted = Objects.requireNonNull(onWorkPromoted, "onWorkPromoted");
    }

    public void start() {
        if (sweeper != null) {
            throw new IllegalStateException("QueueMaintenance already started");
        }
        BackgroundLoop loop = new BackgroundLoop(config.workerId() + "-maintenance",
                config.maintenanceInterval(), config.maintenanceInterval(), () -> {
                    sweep();
                    return false;
                });
        sweeper = loop;
        loop.start();
        log.debug("Maintenance sweeper started, every {}", config.maintenanceInterval());
    }

    /**
     * Runs one sweep immediately. Exposed so tests can advance the world deterministically instead
     * of waiting for the sweeper thread.
     *
     * @return promoted and reclaimed counts
     */
    public SweepResult sweep() {
        Instant now = clock.instant();
        int promoted = store.promoteDueJobs(now, config.maintenanceBatchSize());
        int reclaimed = store.reclaimExpiredLeases(now, config.maintenanceBatchSize());
        if (reclaimed > 0) {
            metrics.leasesReclaimed(reclaimed);
            log.warn("Reclaimed {} job(s) from workers that never reported back", reclaimed);
        }
        if (promoted > 0) {
            log.debug("Promoted {} due job(s) to PENDING", promoted);
        }
        if (promoted + reclaimed > 0) {
            // New claimable work exists; do not make the dispatcher wait out its poll interval.
            onWorkPromoted.run();
        }
        return new SweepResult(promoted, reclaimed);
    }

    @Override
    public void close() {
        BackgroundLoop loop = sweeper;
        if (loop != null) {
            loop.stop();
            sweeper = null;
        }
    }

    /** @param promoted jobs moved SCHEDULED/FAILED -> PENDING
     *  @param reclaimed jobs moved RUNNING -> PENDING after their lease expired */
    public record SweepResult(int promoted, int reclaimed) {
    }
}
