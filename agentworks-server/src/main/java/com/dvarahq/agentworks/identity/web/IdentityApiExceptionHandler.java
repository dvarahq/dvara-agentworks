package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import com.dvarahq.agentworks.identity.IdentityException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.Locale;

/** RFC 9457 problems with stable codes for the identity APIs; details are fixed text, never request content. */
@org.springframework.core.annotation.Order(com.dvarahq.agentworks.api.web.ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = {AuthController.class, ApiTokenController.class, MembershipController.class,
        UserAdministrationController.class})
public final class IdentityApiExceptionHandler {

    @ExceptionHandler(IdentityException.class)
    ResponseEntity<ProblemDetail> identity(IdentityException exception) {
        HttpStatus status = switch (exception.code()) {
            case IdentityException.AUTHENTICATION_FAILED -> HttpStatus.UNAUTHORIZED;
            case IdentityException.BOOTSTRAP_UNAVAILABLE, IdentityException.ROLE_NOT_GRANTABLE,
                 IdentityException.WORKSPACE_SCOPE_UNAVAILABLE -> HttpStatus.FORBIDDEN;
            case IdentityException.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case IdentityException.USERNAME_TAKEN, IdentityException.LAST_ADMINISTRATOR -> HttpStatus.CONFLICT;
            case IdentityException.TOO_MANY_ATTEMPTS -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"agentworks\"");
        return response.body(problem(status, exception.code(), exception.getMessage()));
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
        problem.setType(URI.create("urn:dvaraos:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        return problem;
    }
}
