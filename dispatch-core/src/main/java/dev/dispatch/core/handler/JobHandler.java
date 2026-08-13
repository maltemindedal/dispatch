package dev.dispatch.core.handler;

/**
 * The unit of user code the queue runs. One handler per job type.
 *
 * <p>Contract:
 * <ul>
 *   <li>Return normally to mark the attempt successful.</li>
 *   <li>Throw anything to mark it failed; the retry policy decides what happens next.</li>
 *   <li>Throw {@link PermanentJobFailureException} to skip retries entirely and dead-letter now.</li>
 *   <li>Handlers should be idempotent. At-least-once delivery is the only honest guarantee a
 *       queue like this can make: a worker can finish a job and die before recording it, and the
 *       visibility timeout will hand that job to someone else.</li>
 *   <li>Handlers run on virtual threads, so blocking I/O is fine and expected. Long CPU-bound
 *       stretches pin the carrier thread, so break those up if you have them.</li>
 * </ul>
 */
@FunctionalInterface
public interface JobHandler {

    /**
     * @param context the job being executed, plus cooperative cancellation signals
     * @throws Exception to fail the attempt
     */
    void handle(JobContext context) throws Exception;
}
