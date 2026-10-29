package com.dvarahq.agentworks.api.web;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.util.Locale;

/**
 * The one error model of the {@code /api/v1} contract (issue #35): an RFC 9457 problem document whose {@code type} is
 * {@code urn:dvaraos:problem:<code in kebab case>} and whose {@code code} is a stable upper-case machine value. The
 * {@code detail} is fixed server text; it never echoes request content, credentials, or internal state beyond what
 * the caller may already read.
 */
public final class ApiProblems {
    /**
     * Order of the controller-scoped exception handlers. They run before {@link ApiFallbackExceptionHandler}, which
     * maps only what they leave unhandled.
     */
    public static final int SPECIFIC_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    /**
     * The code of a client error (4xx) that no more specific code describes, whatever its status, including one that
     * {@link HttpStatus} does not name, such as 499: "another 4xx" in the published contract (#35, #207). The REST
     * fallback handler, Tomcat's error report, and the Console all use it.
     */
    public static final String CLIENT_ERROR_CODE = "REQUEST_REJECTED";

    /** The fixed text of a {@link #CLIENT_ERROR_CODE} problem. */
    public static final String CLIENT_ERROR_DETAIL = "The request was rejected.";

    /** The code of a server error (5xx) that no more specific code describes. */
    public static final String SERVER_ERROR_CODE = "INTERNAL_ERROR";

    /** The fixed text of a {@link #SERVER_ERROR_CODE} problem. */
    public static final String SERVER_ERROR_DETAIL = "An unexpected error occurred.";

    /** The {@code Retry-After} of every 503 {@code SERVICE_UNAVAILABLE}, in seconds. */
    public static final String RETRY_AFTER_SECONDS = "1";

    /**
     * The status an error outside the controllers is answered with (#207 review), by the error page and by Tomcat's
     * error report alike: a client error as it is, including one {@link HttpStatus} does not name; 503 as it is, with
     * {@link #RETRY_AFTER_SECONDS}; and any other status as 500 {@code INTERNAL_ERROR} with an {@code errorId}. The
     * contract names 500 and 503 as its only server errors, so a 502, 504, or 505 from a filter or the container is an
     * unexpected failure that an operator finds by its {@code errorId}.
     */
    public static int unhandledStatus(int status) {
        if (status >= 400 && status < 500) return status;
        return status == HttpStatus.SERVICE_UNAVAILABLE.value() ? status : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private ApiProblems() {}

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type(code));
        problem.setProperty("code", code);
        return problem;
    }

    /** The generic code of a status that no operation-specific code describes. */
    public static String code(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "MALFORMED_REQUEST";
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case FORBIDDEN -> "ACCESS_DENIED";
            case NOT_FOUND -> "NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
            case CONTENT_TOO_LARGE -> "CONTENT_TOO_LARGE";
            case URI_TOO_LONG -> "URI_TOO_LONG";
            case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
            case REQUEST_HEADER_FIELDS_TOO_LARGE -> "HEADERS_TOO_LARGE";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> status.is4xxClientError() ? CLIENT_ERROR_CODE : SERVER_ERROR_CODE;
        };
    }

    /**
     * The generic code of any status code: the code of the status {@link HttpStatus} names, else the client-error
     * default for a 4xx (such as 499) and the server-error default otherwise (#207). The status itself is kept.
     */
    public static String code(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        if (known != null) return code(known);
        return status >= 400 && status < 500 ? CLIENT_ERROR_CODE : SERVER_ERROR_CODE;
    }

    /** The fixed text of a generic problem of {@code status}. */
    public static String detail(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request could not be read.";
            case UNAUTHORIZED -> "Authentication is required.";
            case FORBIDDEN -> "The principal may not perform this operation.";
            case NOT_FOUND -> "The resource was not found.";
            case METHOD_NOT_ALLOWED -> "The method is not supported by this resource.";
            case NOT_ACCEPTABLE -> "No acceptable representation is available for this operation.";
            case UNSUPPORTED_MEDIA_TYPE -> "The request content type is not supported by this operation.";
            case SERVICE_UNAVAILABLE -> "The service is temporarily unavailable; retry the request.";
            default -> status.is4xxClientError() ? CLIENT_ERROR_DETAIL : SERVER_ERROR_DETAIL;
        };
    }

    /** The fixed text of a generic problem of any status code, as {@link #code(int)} chooses its code. */
    public static String detail(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        if (known != null) return detail(known);
        return status >= 400 && status < 500 ? CLIENT_ERROR_DETAIL : SERVER_ERROR_DETAIL;
    }

    /** A generic problem of {@code status}. */
    public static ProblemDetail problem(HttpStatus status) {
        return problem(status, code(status), detail(status));
    }

    /**
     * A generic problem of any status code, which keeps that status even when {@link HttpStatus} does not name it
     * (#207): an unknown 4xx is {@link #CLIENT_ERROR_CODE}, never 400's {@code MALFORMED_REQUEST}.
     */
    public static ProblemDetail problem(HttpStatusCode status) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail(status.value()));
        String code = code(status.value());
        problem.setType(type(code));
        problem.setProperty("code", code);
        return problem;
    }

    public static URI type(String code) {
        return URI.create("urn:dvaraos:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
