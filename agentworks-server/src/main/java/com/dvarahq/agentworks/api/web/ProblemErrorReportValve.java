package com.dvarahq.agentworks.api.web;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Tomcat's error report for requests the server rejects before any application code runs, for example a request
 * whose URL encoding is invalid (issue #35). Instead of Tomcat's HTML page it writes the same problem document as
 * every other error, with a fixed code and text and nothing from the request. Its statuses follow the error page's
 * ({@link ApiProblems#unhandledStatus}, #207 review): a server error other than 503 is a logged 500 with an
 * {@code errorId}, and a 503 carries {@code Retry-After}.
 */
public final class ProblemErrorReportValve extends ErrorReportValve {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProblemErrorReportValve.class);

    public ProblemErrorReportValve() {
        setShowReport(false);
        setShowServerInfo(false);
    }

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int reported = response.getStatus();
        if (reported < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) return;
        int status = ApiProblems.unhandledStatus(reported);
        String errorId = null;
        if (status == 500) {
            errorId = java.util.UUID.randomUUID().toString();
            if (throwable == null) {
                LOG.error("unexpected failure reported by the server (status {}) errorId={}", reported, errorId);
            } else {
                // Tomcat logs a request failure with its stack trace before its ErrorReportValve runs. Keep the
                // response's errorId searchable without emitting the same stack a second time (#210).
                LOG.warn("failure already logged by the server (status {}) errorId={} cause={}", reported, errorId,
                        throwable.getClass().getName());
            }
        }
        try {
            if (status != reported) response.setStatus(status);
            if (status == 503 && response.getHeader("Retry-After") == null) {
                response.setHeader("Retry-After", ApiProblems.RETRY_AFTER_SECONDS);
            }
            response.setContentType("application/problem+json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Writer writer = response.getReporter();
            if (writer == null) return;
            writer.write(body(status, errorId));
            response.finishResponse();
        } catch (IOException | IllegalStateException ignored) {
            // The connection is unusable; there is nothing more to report.
        }
    }

    /** As {@link #body(int, String)}, without an {@code errorId}. */
    static String body(int status) {
        return body(status, null);
    }

    /**
     * The problem document for {@code status}: the generic code and text of {@link ApiProblems}, the one source the
     * REST API and the Console use too (#207), so an unknown 4xx is {@code REQUEST_REJECTED} with its own status. The
     * code, text, and {@code errorId} (a UUID) are fixed server values that need no JSON escaping.
     */
    static String body(int status, String errorId) {
        String code = ApiProblems.code(status);
        return "{\"type\":\"" + ApiProblems.type(code) + "\",\"status\":" + status + ",\"detail\":\""
                + ApiProblems.detail(status) + "\",\"code\":\"" + code + "\""
                + (errorId == null ? "" : ",\"errorId\":\"" + errorId + "\"") + "}";
    }
}
