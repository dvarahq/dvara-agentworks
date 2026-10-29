package com.dvarahq.agentworks.artifact.web;

import com.dvarahq.agentworks.api.web.ApiProblems;
import com.dvarahq.agentworks.artifact.ArtifactStoreException;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@org.springframework.core.annotation.Order(ApiProblems.SPECIFIC_ORDER)
@RestControllerAdvice(assignableTypes = ArtifactController.class)
public final class ArtifactApiExceptionHandler {
    @ExceptionHandler(ArtifactStoreException.class)
    ProblemDetail artifact(ArtifactStoreException failure) {
        return switch (failure.reason()) {
            case NOT_FOUND_OR_DENIED -> problem(HttpStatus.NOT_FOUND, "ARTIFACT_NOT_FOUND",
                    "The artifact was not found.");
            case INVALID_RANGE -> problem(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "INVALID_ARTIFACT_RANGE",
                    "The requested artifact range is invalid.");
            case INTEGRITY_FAILED -> problem(HttpStatus.UNPROCESSABLE_CONTENT, "ARTIFACT_INTEGRITY_FAILED",
                    "Artifact integrity verification failed.");
            case LIMIT_EXCEEDED -> problem(HttpStatus.CONTENT_TOO_LARGE, "ARTIFACT_LIMIT_EXCEEDED",
                    "The artifact exceeds a configured limit.");
            case QUARANTINED -> problem(HttpStatus.LOCKED, "ARTIFACT_QUARANTINED",
                    "The artifact is quarantined.");
            case PINNED -> problem(HttpStatus.CONFLICT, "ARTIFACT_PINNED",
                    "The artifact is retained by an active pin or resumable run.");
            case INVALID_STATE -> problem(HttpStatus.CONFLICT, "ARTIFACT_STATE_CONFLICT",
                    "The artifact lifecycle does not permit this operation.");
            case UNSUPPORTED_MEDIA_TYPE -> problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "The artifact media type is not supported.");
            case UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "ARTIFACT_UNAVAILABLE",
                    "The artifact service is temporarily unavailable.");
        };
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ProblemDetail invalid(Exception failure) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT_REQUEST", "The artifact request is invalid.");
    }

    @ExceptionHandler(WorkspaceScopeUnavailableException.class)
    ProblemDetail noWorkspace(WorkspaceScopeUnavailableException failure) {
        return problem(HttpStatus.FORBIDDEN, "WORKSPACE_SCOPE_UNAVAILABLE", failure.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        return ApiProblems.problem(status, code, detail);
    }
}
