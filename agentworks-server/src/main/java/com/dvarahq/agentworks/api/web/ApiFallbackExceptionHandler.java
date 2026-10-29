package com.dvarahq.agentworks.api.web;

import com.dvarahq.agentworks.identity.web.RequestBodyLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.beans.TypeMismatchException;

import java.util.UUID;

/**
 * The last-resort mapping of every exception a controller-scoped handler leaves unhandled into the one problem
 * model (issue #35). Framework request errors become 4xx problems with fixed text; database unavailability becomes
 * 503 with {@code Retry-After}; anything else becomes 500 {@code INTERNAL_ERROR} with an opaque {@code errorId} that
 * is logged with the failure, so no exception message, class name, SQL, or stack trace reaches the client.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public final class ApiFallbackExceptionHandler {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiFallbackExceptionHandler.class);

    @ExceptionHandler(ApiRequestException.class)
    ResponseEntity<ProblemDetail> request(ApiRequestException exception) {
        ProblemDetail problem = ApiProblems.problem(exception.status(), exception.code(), exception.getMessage());
        exception.properties().forEach(problem::setProperty);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status());
        exception.headers().forEach((name, value) -> response.header(name, value));
        return response.body(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException exception) {
        if (HttpHeaders.IF_MATCH.equalsIgnoreCase(exception.getHeaderName())) {
            return ApiProblems.problem(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                    "This mutation requires the If-Match header.");
        }
        return ApiProblems.problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, TypeMismatchException.class,
            ServletRequestBindingException.class, HandlerMethodValidationException.class})
    ProblemDetail malformed(Exception exception) {
        if (RequestBodyLimitFilter.causedByLimit(exception)) {
            return ApiProblems.problem(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "The request body exceeds the configured limit.");
        }
        return ApiProblems.problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read.");
    }

    /**
     * 415, 406, 405, and 404 from the framework, with the headers the framework adds to them ({@code Accept} on 415,
     * {@code Allow} on 405, as RFC 9110 requires).
     */
    @ExceptionHandler({HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class,
            HttpRequestMethodNotSupportedException.class, NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<ProblemDetail> framework(Exception exception) {
        return generic((org.springframework.web.ErrorResponse) exception);
    }

    /**
     * A framework error's generic problem, with its own status and headers. A status that {@link HttpStatus} does not
     * name keeps its value: an unknown 4xx, such as 499, is {@code REQUEST_REJECTED}, as the contract documents for
     * "another 4xx" (#207), never 400 {@code MALFORMED_REQUEST}.
     */
    static ResponseEntity<ProblemDetail> generic(org.springframework.web.ErrorResponse error) {
        org.springframework.http.HttpStatusCode status = error.getStatusCode();
        return ResponseEntity.status(status).headers(error.getHeaders()).body(ApiProblems.problem(status));
    }

    @ExceptionHandler({TransientDataAccessException.class, RecoverableDataAccessException.class,
            DataAccessResourceFailureException.class})
    ResponseEntity<ProblemDetail> unavailable(Exception exception) {
        LOG.warn("request failed because the database is unavailable: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                        "The service is temporarily unavailable; retry the request."));
    }

    /** A stream whose client left, or whose response already started, has nothing more to write. */
    @ExceptionHandler({AsyncRequestNotUsableException.class, AsyncRequestTimeoutException.class})
    ProblemDetail asyncEnded(Exception exception, HttpServletResponse response) {
        if (response.isCommitted()) return null;
        return ApiProblems.problem(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable; retry the request.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletResponse response) {
        if (RequestBodyLimitFilter.causedByLimit(exception)) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(ApiProblems.problem(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                            "The request body exceeds the configured limit."));
        }
        // Any other framework request error keeps its client-error status and headers, with its generic code.
        if (exception instanceof org.springframework.web.ErrorResponse framework
                && framework.getStatusCode().is4xxClientError()) {
            return response.isCommitted() ? null : generic(framework);
        }
        String errorId = UUID.randomUUID().toString();
        logUnexpected(LOG, "unexpected API failure", errorId, exception);
        if (response.isCommitted()) return null;
        ProblemDetail problem = ApiProblems.problem(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setProperty("errorId", errorId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    /**
     * Logs an unexpected failure once with its {@code errorId}: at ERROR, except a client that disconnected, which is
     * no server fault and is logged at DEBUG (#200, #203). This handler resolves every failure that no other handler
     * resolves, for the REST API and for the Console's calls of it alike, so it is where the two are told apart.
     *
     * <p>A disconnect is recognized by the container's own exception types in the cause chain, never by message text:
     * a server-side failure whose root cause reads "connection reset by peer" (a database or an outbound call) is a
     * server fault and stays at ERROR, so its {@code errorId} can be found in the log.
     */
    public static void logUnexpected(org.slf4j.Logger log, String message, String errorId, Throwable failure) {
        if (clientDisconnected(failure)) {
            log.debug("{} (client disconnected) errorId={}", message, errorId, failure);
        } else {
            log.error("{} errorId={}", message, errorId, failure);
        }
    }

    /**
     * Whether {@code failure} is Tomcat's report of a client that went away: a {@code ClientAbortException} anywhere in
     * its causes, or Spring's {@code AsyncRequestNotUsableException} caused by an I/O failure, or the same exception
     * Spring raises, without a cause, for any later use of a response it already marked failed ("Response not usable
     * after response errors.", #207): Spring marks it so only when writing to the client failed or the container
     * reported an error on the async request, which is the disconnect. Spring's same exception after the request
     * completed ("Response not usable after async request completion.") is a late write by the server, and stays an
     * error. That message is Spring's own constant, not a cause's text, so no root cause that merely reads like a
     * disconnect matches it. The server runs on Tomcat only, so no other container's types are listed.
     */
    static boolean clientDisconnected(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof org.apache.catalina.connector.ClientAbortException) return true;
            if (current instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException unusable
                    && (unusable.getCause() instanceof java.io.IOException
                    || RESPONSE_FAILED.equals(unusable.getMessage()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The message of Spring's {@code AsyncRequestNotUsableException} for a response already marked failed
     * ({@code StandardServletAsyncWebRequest}, Spring Framework 7.0); pinned by
     * {@code ApiFallbackExceptionHandlerTest.springsResponseErrorsMessageIsPinned}.
     */
    static final String RESPONSE_FAILED = "Response not usable after response errors.";
}
