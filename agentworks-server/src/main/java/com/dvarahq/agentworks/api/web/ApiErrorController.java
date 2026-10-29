package com.dvarahq.agentworks.api.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renders errors that never reached a controller, such as a request the servlet container or the security firewall
 * rejected, as the same problem model as every other error (issue #35). It replaces Spring Boot's default error
 * body, which echoes the request path; this body carries only the status and a fixed code and text.
 */
@RestController
public final class ApiErrorController implements ErrorController {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ApiErrorController.class);

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatusCode status = status(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        ProblemDetail problem = ApiProblems.problem(status);
        if (status.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            // A failure outside any controller, for example in a filter: logged with an id the client can quote.
            String errorId = java.util.UUID.randomUUID().toString();
            Object failure = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            LOG.error("unexpected failure outside the API handlers errorId={}", errorId,
                    failure instanceof Throwable throwable ? throwable : null);
            problem.setProperty("errorId", errorId);
        }
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        // Every 503 says when to retry, as the contract describes it (#207 review); one the failure set is kept.
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value() && !response.containsHeader(HttpHeaders.RETRY_AFTER)) {
            answer.header(HttpHeaders.RETRY_AFTER, ApiProblems.RETRY_AFTER_SECONDS);
        }
        return answer.body(problem);
    }

    /** The status to answer ({@link ApiProblems#unhandledStatus}); a missing status is 500. */
    static HttpStatusCode status(Object code) {
        return HttpStatusCode.valueOf(ApiProblems.unhandledStatus(code instanceof Integer value ? value : 500));
    }
}
