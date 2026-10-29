package com.dvarahq.agentworks.runplane.session.web;

import com.dvarahq.agentworks.api.web.ApiProblems;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.session.SessionAuthorityException;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@org.springframework.core.annotation.Order(ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = SessionController.class)
public final class SessionApiExceptionHandler {
    @ExceptionHandler(SessionAuthorityException.class)
    ProblemDetail session(SessionAuthorityException exception) {
        HttpStatus status = exception.code() == SessionAuthorityException.Code.NOT_FOUND
                ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
        String detail = switch (exception.code()) {
            case NOT_FOUND -> "The session or inbox message was not found.";
            case RUN_NOT_IN_SESSION -> "The correlated run does not belong to this session.";
            case RUN_PINS_MISMATCH -> "The inbox message does not match its pinned run.";
            case COHORT_NOT_ADMITTING -> "The pinned cohort no longer admits this inbox transition.";
            case IDEMPOTENCY_CONFLICT -> "The inbox idempotency key conflicts with an existing message.";
            case ALREADY_TERMINAL -> "The inbox message already has a terminal outcome.";
            case ALREADY_CLAIMED -> "The inbox message has a live worker claim.";
            case STALE_CLAIM, STALE_VERSION -> "The inbox message changed concurrently; retry from its latest state.";
            case INVALID_TRANSITION -> "The requested session or inbox transition is invalid.";
        };
        return ApiProblems.problem(status, exception.code().name(), detail);
    }

    @ExceptionHandler(RunAdmissionRejectedException.class)
    ProblemDetail admission(RunAdmissionRejectedException exception) {
        return ApiProblems.problem(HttpStatus.CONFLICT, exception.code(),
                "The session message could not create a new root run through current routing.");
    }

    @ExceptionHandler(LifecycleRetryExhaustedException.class)
    ProblemDetail retry(LifecycleRetryExhaustedException exception) {
        return ApiProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "LIFECYCLE_RETRY_EXHAUSTED",
                "The session delivery could not complete after bounded retries.");
    }
}
