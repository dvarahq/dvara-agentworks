package com.dvarahq.agentworks.runplane.operation.web;

import com.dvarahq.agentworks.api.web.ApiProblems;
import com.dvarahq.agentworks.operation.DurableOperationAuthorityException;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@org.springframework.core.annotation.Order(ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = DurableOperationController.class)
public final class DurableOperationApiExceptionHandler {
    @ExceptionHandler(DurableOperationAuthorityException.class)
    ProblemDetail operation(DurableOperationAuthorityException exception) {
        HttpStatus status = switch (exception.code()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CALLBACK_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case PROVIDER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case PROVIDER_NOT_FOUND -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.CONFLICT;
        };
        String detail = switch (exception.code()) {
            case NOT_FOUND -> "The durable operation was not found.";
            case PROVIDER_NOT_FOUND -> "The durable operation provider is not configured.";
            case RUN_PINS_MISMATCH, RUN_NOT_IN_SESSION -> "The operation does not match its pinned run and session.";
            case COHORT_NOT_ADMITTING -> "The pinned cohort no longer admits this operation transition.";
            case IDEMPOTENCY_CONFLICT -> "The operation idempotency key conflicts with an existing request.";
            case CALLBACK_UNAUTHORIZED -> "The operation callback token is invalid.";
            case ALREADY_TERMINAL -> "The operation already has a terminal outcome.";
            case RESULT_NOT_READY -> "The provider has not supplied a terminal result.";
            case INVALID_TRANSITION -> "The requested operation transition is invalid.";
            case PROVIDER_UNAVAILABLE -> "The durable operation provider is temporarily unavailable.";
        };
        return ApiProblems.problem(status, exception.code().name(), detail);
    }

    @ExceptionHandler(RunEngineException.class)
    ProblemDetail run(RunEngineException exception) {
        return ApiProblems.problem("NOT_FOUND".equals(exception.code()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                exception.code(), "The operation outcome could not resume its pinned run.");
    }

    @ExceptionHandler(LifecycleRetryExhaustedException.class)
    ProblemDetail retry(LifecycleRetryExhaustedException exception) {
        return ApiProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "LIFECYCLE_RETRY_EXHAUSTED",
                "The operation transition could not complete after bounded retries.");
    }
}
