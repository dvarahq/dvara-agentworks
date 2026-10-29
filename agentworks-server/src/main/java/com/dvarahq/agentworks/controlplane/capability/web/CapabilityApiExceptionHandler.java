package com.dvarahq.agentworks.controlplane.capability.web;

import com.dvarahq.agentworks.api.web.ApiProblems;
import com.dvarahq.agentworks.capability.CapabilityNotFoundException;
import com.dvarahq.agentworks.capability.CapabilityLifecycleConflictException;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.Locale;

/** Stable RFC 9457 failures for the capability catalog API. */
@org.springframework.core.annotation.Order(ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = CapabilityCatalogController.class)
public final class CapabilityApiExceptionHandler {
    @ExceptionHandler(CapabilityNotFoundException.class)
    ProblemDetail notFound(CapabilityNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "CAPABILITY_CONFLICT",
                "The capability conflicts with existing catalog state.");
    }

    @ExceptionHandler(CapabilityLifecycleConflictException.class)
    ProblemDetail lifecycle(CapabilityLifecycleConflictException exception) {
        return problem(HttpStatus.CONFLICT, "CAPABILITY_LIFECYCLE_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_CAPABILITY", "The capability request is invalid.");
    }

    @ExceptionHandler(WorkspaceScopeUnavailableException.class)
    ProblemDetail noWorkspace(WorkspaceScopeUnavailableException exception) {
        return problem(HttpStatus.FORBIDDEN, "WORKSPACE_SCOPE_UNAVAILABLE", exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:dvaraos:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
