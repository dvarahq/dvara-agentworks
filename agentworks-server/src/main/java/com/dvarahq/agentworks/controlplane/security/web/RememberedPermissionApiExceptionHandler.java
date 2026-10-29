package com.dvarahq.agentworks.controlplane.security.web;

import com.dvarahq.agentworks.api.web.ApiProblems;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import com.dvarahq.agentworks.controlplane.security.RememberedPermissionNotFoundException;
import com.dvarahq.agentworks.controlplane.security.RememberedPermissionConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.Locale;

@org.springframework.core.annotation.Order(ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = RememberedPermissionController.class)
public final class RememberedPermissionApiExceptionHandler {
    @ExceptionHandler(RememberedPermissionNotFoundException.class)
    ProblemDetail notFound(RememberedPermissionNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "REMEMBERED_PERMISSION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "REMEMBERED_PERMISSION_CONFLICT",
                "The remembered permission conflicts with current state.");
    }

    @ExceptionHandler(RememberedPermissionConflictException.class)
    ProblemDetail conflict(RememberedPermissionConflictException exception) {
        return problem(HttpStatus.CONFLICT, "REMEMBERED_PERMISSION_CONFLICT", exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REMEMBERED_PERMISSION",
                "The remembered permission request is invalid.");
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
