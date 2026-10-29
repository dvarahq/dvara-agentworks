package com.dvarahq.agentworks.api.web;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The error page's problem for errors that never reached a controller (#35, #207). */
@ExtendWith(OutputCaptureExtension.class)
@ResourceLock("output-capture")
class ApiErrorControllerTest {

    /**
     * #207 review: a client error whose status HttpStatus does not name, such as 499, keeps its status and is
     * REQUEST_REJECTED, as on the fallback handler; before, the error page answered it as a logged 500.
     */
    @Test
    void anUnknownClientErrorKeepsItsStatusAndIsRejected(CapturedOutput output) {
        var answer = error(499, new MockHttpServletResponse());

        assertThat(answer.getStatusCode().value()).isEqualTo(499);
        assertThat(answer.getBody().getProperties()).containsEntry("code", "REQUEST_REJECTED")
                .doesNotContainKey("errorId");
        assertThat(answer.getBody().getDetail()).isEqualTo("The request was rejected.");
        assertThat(output.getAll()).doesNotContain("ERROR");
    }

    @Test
    void knownStatusesKeepTheirCodesAndOtherServerErrorsAre500() {
        assertThat(error(404, new MockHttpServletResponse()).getBody().getProperties())
                .containsEntry("code", "NOT_FOUND");
        for (int status : new int[]{502, 504, 505, 599, 200}) {
            var answer = error(status, new MockHttpServletResponse());
            assertThat(answer.getStatusCode().value()).as("%d", status).isEqualTo(500);
            assertThat(answer.getBody().getProperties()).containsEntry("code", "INTERNAL_ERROR").containsKey("errorId");
        }
    }

    /** #207 review: a 503 from the error page says when to retry, and keeps a Retry-After the failure already set. */
    @Test
    void a503CarriesRetryAfter() {
        var answer = error(503, new MockHttpServletResponse());
        assertThat(answer.getStatusCode().value()).isEqualTo(503);
        assertThat(answer.getBody().getProperties()).containsEntry("code", "SERVICE_UNAVAILABLE");
        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("1");

        var response = new MockHttpServletResponse();
        response.setHeader("Retry-After", "30");
        assertThat(error(503, response).getHeaders().containsHeader("Retry-After")).isFalse();
    }

    private static org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> error(int status,
            MockHttpServletResponse response) {
        var request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        return new ApiErrorController().error(request, response);
    }
}
