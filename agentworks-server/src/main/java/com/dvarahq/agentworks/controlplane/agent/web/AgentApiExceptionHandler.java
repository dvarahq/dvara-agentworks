package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.controlplane.agent.AgentLifecycleConflictException;
import com.dvarahq.agentworks.controlplane.agent.AgentResourceNotFoundException;
import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import com.dvarahq.agentworks.spec.DiagnosticCode;
import com.dvarahq.agentworks.spec.SpecDiagnostic;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** RFC 9457 problem responses with stable codes and typed AgentSpec diagnostics. */
@org.springframework.core.annotation.Order(com.dvarahq.agentworks.api.web.ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = AgentDefinitionController.class)
public final class AgentApiExceptionHandler {

    @ExceptionHandler(AgentSpecValidationException.class)
    ProblemDetail invalid(AgentSpecValidationException exception) {
        List<SpecDiagnostic> diagnostics = exception.diagnostics();
        HttpStatus status = HttpStatus.UNPROCESSABLE_CONTENT;
        String code = "AGENT_SPEC_INVALID";
        if (has(diagnostics, DiagnosticCode.DOCUMENT_TOO_LARGE)) {
            status = HttpStatus.CONTENT_TOO_LARGE;
            code = "DOCUMENT_TOO_LARGE";
        } else if (has(diagnostics, DiagnosticCode.MALFORMED_DOCUMENT)) {
            status = HttpStatus.BAD_REQUEST;
            code = "MALFORMED_DOCUMENT";
        }
        ProblemDetail problem = problem(status, code, "The agent specification is invalid.");
        problem.setProperty("diagnostics", diagnostics.stream().map(diagnostic -> Map.of(
                "code", diagnostic.code().name(), "section", diagnostic.section().name(),
                "path", diagnostic.path(), "message", diagnostic.message())).toList());
        return problem;
    }

    @ExceptionHandler(AgentResourceNotFoundException.class)
    ProblemDetail notFound(AgentResourceNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AgentLifecycleConflictException.class)
    ProblemDetail conflict(AgentLifecycleConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(WorkspaceScopeUnavailableException.class)
    ProblemDetail noWorkspace(WorkspaceScopeUnavailableException exception) {
        return problem(HttpStatus.FORBIDDEN, "WORKSPACE_SCOPE_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail unreadable(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:dvaraos:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }

    private static boolean has(List<SpecDiagnostic> diagnostics, DiagnosticCode code) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }
}
