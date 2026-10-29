package com.dvarahq.agentworks.runplane.interaction.web;

import com.dvarahq.agentworks.api.web.ApiProblems;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable RFC 9457 refusals for the human-interaction API. */
@org.springframework.core.annotation.Order(ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = HumanInteractionController.class)
public final class HumanInteractionApiExceptionHandler {
    @ExceptionHandler(HumanInteractionAuthorityException.class)
    ProblemDetail interaction(HumanInteractionAuthorityException exception) {
        HttpStatus status = switch (exception.code()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED_RESPONDER -> HttpStatus.FORBIDDEN;
            case INVALID_RESPONSE -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.CONFLICT;
        };
        String detail = switch (exception.code()) {
            case NOT_FOUND -> "The interaction was not found.";
            case UNAUTHORIZED_RESPONDER -> "The principal is not assigned to this interaction.";
            case INVALID_RESPONSE -> "The response does not match the interaction request.";
            case INVALID_TRANSITION -> "The requested outcome does not apply to this interaction.";
            case ALREADY_TERMINAL -> "The interaction already has a terminal outcome.";
            case DEADLINE_PASSED -> "The interaction deadline has passed.";
            case DEADLINE_NOT_REACHED -> "The interaction deadline has not been reached.";
            case IDEMPOTENCY_CONFLICT -> "The interaction idempotency key conflicts with an existing request.";
            case RUN_PINS_MISMATCH -> "The interaction does not match its pinned run.";
            case COHORT_NOT_ADMITTING -> "The interaction's cohort no longer admits this response.";
            case CREDENTIAL_UNAVAILABLE -> "A current credential is not available for this authorization scope.";
        };
        return ApiProblems.problem(status, exception.code().name(), detail);
    }

    @ExceptionHandler(RunEngineException.class)
    ProblemDetail run(RunEngineException exception) {
        HttpStatus status = "NOT_FOUND".equals(exception.code()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        String detail = "NOT_FOUND".equals(exception.code()) ? "The pinned run was not found."
                : "The interaction outcome could not resume its pinned run.";
        return ApiProblems.problem(status, exception.code(), detail);
    }

    @ExceptionHandler(LifecycleRetryExhaustedException.class)
    ProblemDetail retry(LifecycleRetryExhaustedException exception) {
        return ApiProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "LIFECYCLE_RETRY_EXHAUSTED",
                "The interaction could not be completed after bounded retries.");
    }
}
