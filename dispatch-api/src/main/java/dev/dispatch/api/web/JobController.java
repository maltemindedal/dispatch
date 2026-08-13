package dev.dispatch.api.web;

import dev.dispatch.api.web.dto.JobResponse;
import dev.dispatch.api.web.dto.SubmitJobRequest;
import dev.dispatch.core.engine.JobQueue;
import dev.dispatch.core.handler.UnknownJobTypeException;
import dev.dispatch.core.job.Job;
import dev.dispatch.core.job.JobState;
import dev.dispatch.core.job.JobSubmission;
import dev.dispatch.core.store.JobFilter;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job endpoints.
 *
 * <p>Thin by design — parse, delegate to {@link JobQueue}, map the result to a status code. The
 * only real thinking here is the 404-versus-409 distinction on the two mutating endpoints: "no
 * such job" and "that job is in the wrong state for this" are different problems and deserve
 * different answers.
 */
@RestController
@RequestMapping("/jobs")
public class JobController {

    private final JobQueue queue;
    private final PayloadCodec payloadCodec;

    public JobController(JobQueue queue, PayloadCodec payloadCodec) {
        this.queue = queue;
        this.payloadCodec = payloadCodec;
    }

    /**
     * Enqueues a job.
     *
     * @return 201 with the created job, or 422 if no handler is registered for the type
     */
    @PostMapping
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request) {
        // Reject unknown types at submission rather than letting the job retry its way to the
        // dead-letter state. A typo in a job type is a client error, and it should read like one.
        if (!queue.hasHandlerFor(request.type())) {
            throw new UnknownJobTypeException(request.type(), queue.registeredJobTypes());
        }

        JobSubmission submission = new JobSubmission(
                request.type(),
                payloadCodec.toStoredPayload(request.payload()),
                request.priority() == null ? JobSubmission.DEFAULT_PRIORITY : request.priority(),
                request.maxRetries() == null
                        ? JobSubmission.DEFAULT_MAX_RETRIES : request.maxRetries(),
                request.scheduledAt());

        Job job = queue.submit(submission);
        return ResponseEntity
                .created(URI.create("/jobs/" + job.id()))
                .body(toResponse(job));
    }

    /** @return 200 with the job, or 404 */
    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return queue.find(id).map(this::toResponse).orElseThrow(() -> new JobNotFoundException(id));
    }

    /**
     * Lists jobs, newest first.
     *
     * @param status restrict to one lifecycle state
     * @param type   restrict to one job type
     * @param limit  page size, at most {@link JobFilter#MAX_LIMIT}
     * @param offset rows to skip
     */
    @GetMapping
    public List<JobResponse> list(
            @RequestParam(required = false) JobState status,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        JobFilter filter = new JobFilter(status, type, limit, offset);
        return queue.list(filter).stream().map(this::toResponse).toList();
    }

    /**
     * Requeues a dead-lettered job with a fresh retry budget.
     *
     * @return 200 with the revived job, 404 if it does not exist, 409 if it is not DEAD
     */
    @PostMapping("/{id}/retry")
    public JobResponse retry(@PathVariable UUID id) {
        Job existing = queue.find(id).orElseThrow(() -> new JobNotFoundException(id));
        return queue.retryDeadJob(id)
                .map(this::toResponse)
                .orElseThrow(() -> new JobConflictException(
                        "Only DEAD jobs can be retried; job " + id + " is " + existing.state()));
    }

    /**
     * Cancels a job that has not started. Cancelling deletes the row — the lifecycle has no
     * CANCELLED state, because a job nobody ran leaves nothing worth keeping.
     *
     * @return 204 on success, 404 if it does not exist, 409 if it is already running or finished
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        Job existing = queue.find(id).orElseThrow(() -> new JobNotFoundException(id));
        if (!queue.cancel(id)) {
            throw new JobConflictException("Only PENDING or SCHEDULED jobs can be cancelled; job "
                    + id + " is " + existing.state());
        }
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private JobResponse toResponse(Job job) {
        return JobResponse.from(job, payloadCodec.fromStoredPayload(job.payload()));
    }
}
