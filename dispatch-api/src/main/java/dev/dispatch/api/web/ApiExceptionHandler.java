package dev.dispatch.api.web;

import dev.dispatch.core.handler.UnknownJobTypeException;
import dev.dispatch.core.job.IllegalJobTransitionException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown job type", e.getMessage());
    }

    /**
     * Unreachable from the endpoints — refusals are decided atomically in the store and arrive as
     * {@code JobActionResult}, not exceptions — but a backstop is cheaper than a 500.
     */
    @ExceptionHandler(IllegalJobTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalJobTransitionException e) {
        log.warn("Illegal job transition surfaced through the API", e);
        return problem(HttpStatus.CONFLICT, "Illegal job transition", e.getMessage());
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

    /** Covers bad query parameters too — an unrecognised {@code ?status=} lands here. */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
