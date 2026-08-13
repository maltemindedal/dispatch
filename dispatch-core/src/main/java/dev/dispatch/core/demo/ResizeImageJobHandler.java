package dev.dispatch.core.demo;

import dev.dispatch.core.handler.JobContext;
import dev.dispatch.core.handler.JobHandler;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pretends to resize an image, taking anywhere from fast to slow.
 *
 * <p>Where the email handler exercises retries, this one exercises the two things variable
 * duration breaks: concurrency limits (a few slow jobs must not starve the rest) and the
 * visibility timeout (a job that outruns its lease gets handed to a second worker while the first
 * is still going). Point {@code maxDuration} past the configured visibility timeout and you can
 * watch exactly that happen.
 */
public final class ResizeImageJobHandler implements JobHandler {

    public static final String TYPE = "resize-image";

    private static final Logger log = LoggerFactory.getLogger(ResizeImageJobHandler.class);

    private final Duration minDuration;
    private final Duration maxDuration;
    private final LongAdder resized = new LongAdder();

    /** 50-400ms per image. */
    public ResizeImageJobHandler() {
        this(Duration.ofMillis(50), Duration.ofMillis(400));
    }

    public ResizeImageJobHandler(Duration minDuration, Duration maxDuration) {
        if (maxDuration.compareTo(minDuration) < 0) {
            throw new IllegalArgumentException("maxDuration must be >= minDuration");
        }
        this.minDuration = minDuration;
        this.maxDuration = maxDuration;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        long millis = durationMillis();
        log.debug("Resizing image for job {} (simulated {}ms)", context.jobId(), millis);

        // Sleep rather than burn CPU: a real resize would be CPU-bound and would pin its carrier
        // thread, which is the one workload virtual threads do not help with.
        Thread.sleep(millis);

        if (context.leaseExpired(java.time.Instant.now())) {
            // Past the visibility deadline the job may already be running elsewhere. Saying so
            // loudly beats silently double-processing.
            log.warn("Job {} overran its visibility lease; another worker may have taken it over",
                    context.jobId());
        }

        resized.increment();
        log.info("Image resized for job {} in {}ms", context.jobId(), millis);
    }

    public long resizedCount() {
        return resized.sum();
    }

    private long durationMillis() {
        long min = minDuration.toMillis();
        long max = maxDuration.toMillis();
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
