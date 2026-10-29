package com.dvarahq.agentworks.controlplane.lifecycle.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleNotFoundException;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.runplane.admission.web.RunAdmissionController;
import com.dvarahq.agentworks.runplane.execution.RunEngineException;
import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetryExhaustedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** RFC 9457 problems with stable codes for the lifecycle and run-admission APIs. */
@org.springframework.core.annotation.Order(com.dvarahq.agentworks.api.web.ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = {ReleaseEnvironmentController.class, DeploymentController.class,
        ReleaseEvidenceController.class, ReleaseLifecycleController.class, PromotionApprovalController.class,
        RunAdmissionController.class, com.dvarahq.agentworks.runplane.admission.web.RunStreamController.class,
        com.dvarahq.agentworks.runplane.admission.web.ReplayController.class})
public final class LifecycleApiExceptionHandler {

    @ExceptionHandler(AgentSpecValidationException.class)
    ProblemDetail invalid(AgentSpecValidationException exception) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST", "The request is invalid.");
        problem.setProperty("diagnostics", exception.diagnostics().stream().map(diagnostic -> Map.of(
                "code", diagnostic.code().name(), "path", diagnostic.path(), "message", diagnostic.message())).toList());
        return problem;
    }

    @ExceptionHandler(LifecycleNotFoundException.class)
    ProblemDetail notFound(LifecycleNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(LifecycleConflictException.class)
    ProblemDetail conflict(LifecycleConflictException exception) {
        HttpStatus status = switch (exception.code()) {
            case "STALE_VERSION" -> HttpStatus.PRECONDITION_FAILED;
            case "INVALID_PRECONDITION", "INVALID_IDEMPOTENCY_KEY", "BUNDLE_INVALID" -> HttpStatus.BAD_REQUEST;
            case "IDEMPOTENCY_UNAVAILABLE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            default -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = problem(status, exception.code(), exception.getMessage());
        if (exception.resultId() != null) problem.setProperty("resultId", exception.resultId());
        return problem;
    }

    @ExceptionHandler(RunAdmissionRejectedException.class)
    ResponseEntity<ProblemDetail> admission(RunAdmissionRejectedException exception) {
        HttpStatus status = switch (exception.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_IDEMPOTENCY_KEY", "CONTINUATION_NOT_EXTERNAL", "IDEMPOTENCY_UNAVAILABLE", "INVALID_TRACE_ID",
                    "INVALID_PAYLOAD", "PAYLOAD_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            case "WORKSPACE_OUTSTANDING_LIMIT", "DEPLOYMENT_OUTSTANDING_LIMIT", "WORKSPACE_QUEUE_FULL",
                    "DEPLOYMENT_QUEUE_FULL", "ADMISSION_RATE_LIMITED", "SANDBOX_CAPACITY_EXHAUSTED",
                    "PLACEMENT_CAPACITY_EXHAUSTED", "TOKEN_BUDGET_EXHAUSTED", "COST_BUDGET_EXHAUSTED" ->
                    HttpStatus.TOO_MANY_REQUESTS;
            case "ADMISSION_DEFERRED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        var response = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS || status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header("Retry-After", "1");
        }
        return response.body(problem(status, exception.code(), exception.getMessage()));
    }

    @ExceptionHandler(RunEngineException.class)
    ProblemDetail run(RunEngineException exception) {
        // Integrity and lookup failures get fixed text; the engine's own message names internal positions.
        String detail = switch (exception.code()) {
            case "NOT_FOUND" -> "The run was not found.";
            case "CORRUPT_HISTORY" -> "The run's history does not verify; an operator must investigate.";
            case "UNKNOWN_EVENT_TYPE", "UNSUPPORTED_EVENT_VERSION" ->
                    "The run's history contains events this node cannot read.";
            default -> exception.getMessage();
        };
        HttpStatus status = switch (exception.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_EVENT", "INVALID_PAYLOAD", "PAYLOAD_TOO_LARGE" -> HttpStatus.UNPROCESSABLE_CONTENT;
            case "INVALID_EVENT_POSITION" -> HttpStatus.BAD_REQUEST;
            case "EVENT_HISTORY_EXPIRED" -> HttpStatus.GONE;
            default -> HttpStatus.CONFLICT;
        };
        return problem(status, exception.code(), detail);
    }

    @ExceptionHandler(LifecycleRetryExhaustedException.class)
    ProblemDetail exhausted(LifecycleRetryExhaustedException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "LIFECYCLE_RETRY_EXHAUSTED", exception.getMessage());
    }

    @ExceptionHandler(WorkspaceScopeUnavailableException.class)
    ProblemDetail noWorkspace(WorkspaceScopeUnavailableException exception) {
        return problem(HttpStatus.FORBIDDEN, "WORKSPACE_SCOPE_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException exception) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                "This mutation requires the " + exception.getHeaderName() + " header.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:dvaraos:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
