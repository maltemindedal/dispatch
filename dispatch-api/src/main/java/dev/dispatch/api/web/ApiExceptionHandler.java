package dev.dispatch.api.web;

import dev.dispatch.core.handler.UnknownJobTypeException;
import dev.dispatch.core.job.IllegalJobTransitionException;
import dev.dispatch.core.store.JobStoreException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns domain exceptions into RFC 9457 problem responses, so clients get a machine-readable body
 * rather than a stack trace or an opaque 500.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * The documented {@code type} of every error body. Set explicitly: it used to be the
     * framework's default, and a framework that stops supplying one would silently drop the field
     * from the JSON.
     */
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail handleNotFound(JobNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Job not found", e.getMessage());
    }

    @ExceptionHandler(JobConflictException.class)
    ProblemDetail handleConflict(JobConflictException e) {
        return problem(HttpStatus.CONFLICT, "Job is in the wrong state", e.getMessage());
    }

    /**
     * Submitting a type nobody handles is unprocessable rather than merely malformed: the request
     * is well-formed JSON describing work this deployment cannot do.
     */
    @ExceptionHandler(UnknownJobTypeException.class)
    ProblemDetail handleUnknownType(UnknownJobTypeException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Unknown job type", e.getMessage());
    }

    /**
     * Unreachable from the endpoints. Refusals are decided atomically in the store and arrive as
     * {@code JobActionResult}, not exceptions, but a backstop is cheaper than a 500.
     */
    @ExceptionHandler(IllegalJobTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalJobTransitionException e) {
        log.warn("Illegal job transition surfaced through the API", e);
        return problem(HttpStatus.CONFLICT, "Illegal job transition", e.getMessage());
    }

    /**
     * The store failed: a lost connection, an exhausted pool, a rejected statement. Its message
     * names drivers, pools and SQL, which is for the log and not for the caller, so the response
     * carries a fixed description and the detail stays server-side.
     */
    @ExceptionHandler(JobStoreException.class)
    ProblemDetail handleStoreFailure(JobStoreException e) {
        log.error("Job store failure", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Job store unavailable",
                "The job store could not complete the request");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "The request body failed validation");
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /**
     * A body that is missing, is not JSON, or is JSON of the wrong shape (a string where a number
     * belongs, an instant that does not parse). The framework's own message here is Jackson's: it
     * quotes the offending text and names Java classes and fields, so the response carries a
     * fixed description and the specifics stay in the debug log.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        log.debug("Unreadable request body", e);
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "The request body is missing or is not valid JSON of the expected shape");
    }

    /** Covers bad query parameters too. An unrecognised {@code ?status=} lands here. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ABOUT_BLANK);
        problem.setTitle(title);
        return problem;
    }
}
