package com.dvarahq.agentworks.api.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Tomcat's error report writes the same generic problems as the REST API (#35, #207). */
@ExtendWith(OutputCaptureExtension.class)
@ResourceLock("output-capture")
class ProblemErrorReportValveTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * #207 review: the valve kept its own code table, so 401, 403, 406, and 415 were REQUEST_REJECTED there and
     * specific codes on the REST API, and 404 or 503 read "The request was rejected." It now uses ApiProblems.
     */
    @Test
    void everyStatusGetsTheApisGenericCodeAndText() {
        for (int status : new int[]{400, 401, 403, 404, 405, 406, 413, 414, 415, 431, 499, 500, 502, 503}) {
            var body = JSON.readTree(ProblemErrorReportValve.body(status));
            var api = ApiProblems.problem(org.springframework.http.HttpStatusCode.valueOf(status));
            assertThat(body.path("status").asInt()).as("%d", status).isEqualTo(status);
            assertThat(body.path("code").asString()).as("%d", status).isEqualTo(api.getProperties().get("code"));
            assertThat(body.path("detail").asString()).as("%d", status).isEqualTo(api.getDetail());
            assertThat(body.path("type").asString()).as("%d", status).isEqualTo(api.getType().toString());
        }
        assertThat(JSON.readTree(ProblemErrorReportValve.body(401)).path("code").asString())
                .isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(JSON.readTree(ProblemErrorReportValve.body(499)).path("code").asString())
                .isEqualTo("REQUEST_REJECTED");
    }

    /**
     * #207 review: the valve answers server errors as the error page does: 502, 504, or 505 as a 500 with an errorId,
     * and a 503 with Retry-After, keeping one already set; client errors keep their status.
     */
    @Test
    void serverErrorsAreAnsweredAsTheErrorPageAnswersThem() {
        for (int reported : new int[]{502, 504, 505}) {
            var response = report(reported, null);
            assertThat(response.getStatus()).as("%d", reported).isEqualTo(500);
            var body = JSON.readTree(response.written.toString());
            assertThat(body.path("code").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(body.path("errorId").asString()).isNotBlank();
        }
        var unavailable = report(503, null);
        assertThat(unavailable.getStatus()).isEqualTo(503);
        assertThat(unavailable.getHeader("Retry-After")).isEqualTo("1");
        assertThat(JSON.readTree(unavailable.written.toString()).path("code").asString())
                .isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(report(503, "30").getHeader("Retry-After")).isEqualTo("30");
        var rejected = report(499, null);
        assertThat(rejected.getStatus()).isEqualTo(499);
        assertThat(rejected.getHeader("Retry-After")).isNull();
        assertThat(JSON.readTree(rejected.written.toString()).path("code").asString()).isEqualTo("REQUEST_REJECTED");
        assertThat(JSON.readTree(rejected.written.toString()).has("errorId")).isFalse();
    }

    /** Issue #210: Tomcat already logged a dispatched throwable; the report valve must not print its stack again. */
    @Test
    void aThrowableAlreadyLoggedByTomcatGetsNoSecondErrorStack(CapturedOutput output) {
        var response = report(500, null, new IllegalStateException("already logged"));
        String errorId = JSON.readTree(response.written.toString()).path("errorId").asString();

        assertThat(errorId).isNotBlank();
        assertThat(output.getAll().lines().filter(line -> line.contains("errorId=" + errorId))).hasSize(1)
                .allMatch(line -> line.contains("WARN"));
        assertThat(output.getAll()).doesNotContain("java.lang.IllegalStateException: already logged");
    }

    /** A Tomcat response that records what the valve writes. */
    static final class Recorded extends org.apache.catalina.connector.Response {
        final java.io.StringWriter written = new java.io.StringWriter();

        Recorded() {
            super(new org.apache.coyote.Response());
        }

        @Override
        public long getContentWritten() {
            return 0;
        }

        @Override
        public java.io.PrintWriter getReporter() {
            return new java.io.PrintWriter(written, true);
        }

        @Override
        public void finishResponse() {
        }
    }

    private static Recorded report(int status, String retryAfter) {
        return report(status, retryAfter, null);
    }

    private static Recorded report(int status, String retryAfter, Throwable failure) {
        var response = new Recorded();
        response.setStatus(status);
        // As sendError does: the response is in error, and its report is still to be written.
        response.getCoyoteResponse().setError();
        if (retryAfter != null) response.setHeader("Retry-After", retryAfter);
        new ProblemErrorReportValve().report(null, response, failure);
        return response;
    }
}
