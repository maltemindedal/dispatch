package dev.dispatch.core.demo;

import dev.dispatch.core.handler.JobContext;
import dev.dispatch.core.handler.JobHandler;
import dev.dispatch.core.handler.PermanentJobFailureException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pretends to send an email, and fails a configurable share of the time — the classic flaky
 * downstream dependency that makes retries and backoff worth watching.
 *
 * <p>Payloads that do not mention a recipient fail <em>permanently</em>: no amount of retrying
 * fixes a malformed request, so those go straight to the dead-letter state. That contrast — a
 * transient network blip versus a bad payload — is the distinction the retry machinery exists to
 * draw.
 */
public final class SendEmailJobHandler implements JobHandler {

    public static final String TYPE = "send-email";

    private static final Logger log = LoggerFactory.getLogger(SendEmailJobHandler.class);

    private final double failureRate;
    private final Duration minLatency;
    private final Duration maxLatency;
    private final LongAdder sent = new LongAdder();
    private final LongAdder attempted = new LongAdder();

    /** 30% transient failures, 20-120ms of simulated latency. */
    public SendEmailJobHandler() {
        this(0.3, Duration.ofMillis(20), Duration.ofMillis(120));
    }

    public SendEmailJobHandler(double failureRate, Duration minLatency, Duration maxLatency) {
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException("failureRate must be within [0, 1]: " + failureRate);
        }
        if (maxLatency.compareTo(minLatency) < 0) {
            throw new IllegalArgumentException("maxLatency must be >= minLatency");
        }
        this.failureRate = failureRate;
        this.minLatency = minLatency;
        this.maxLatency = maxLatency;
    }

    @Override
    public void handle(JobContext context) throws Exception {
        attempted.increment();
        String payload = context.payload();
        if (payload == null || !payload.contains("\"to\"")) {
            throw new PermanentJobFailureException(
                    "Payload has no \"to\" field; retrying will not conjure one up: " + payload);
        }

        // Blocking sleep on a virtual thread: cheap, and it yields the carrier thread.
        Thread.sleep(randomLatencyMillis());

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new java.io.IOException(
                    "SMTP server refused the message (simulated transient failure), attempt "
                            + context.attempt());
        }

        sent.increment();
        log.info("Email sent for job {} on attempt {}", context.jobId(), context.attempt());
    }

    public long sentCount() {
        return sent.sum();
    }

    public long attemptCount() {
        return attempted.sum();
    }

    private long randomLatencyMillis() {
        long min = minLatency.toMillis();
        long max = maxLatency.toMillis();
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
