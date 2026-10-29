package com.dvarahq.agentworks.api.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/** How the fallback handler logs the failures it answers as 500 {@code INTERNAL_ERROR} (#35, #200, #203). */
@ExtendWith(OutputCaptureExtension.class)
@ResourceLock("output-capture")
class ApiFallbackExceptionHandlerTest {

    /** #203: a REST client that disconnected is no server fault; it is logged at DEBUG, not ERROR. */
    @Test
    void aClientDisconnectIsNotLoggedAsAnError(CapturedOutput output) {
        var answer = new ApiFallbackExceptionHandler().unexpected(new UncheckedIOException(
                new org.apache.catalina.connector.ClientAbortException(new IOException("Broken pipe"))),
                new MockHttpServletResponse());

        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(answer.getBody().getProperties()).containsKey("errorId");
        assertThat(output.getAll()).doesNotContain("ERROR");
    }

    /**
     * #203 review: Spring's AsyncRequestNotUsableException is a disconnect only when an I/O failure caused it; without
     * one ("Response not usable after async request completion") it is the server writing late, which stays an error.
     */
    @Test
    void anUnusableAsyncResponseIsADisconnectOnlyWithAnIoCause(CapturedOutput output) {
        var disconnected = new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                "ServletOutputStream failed to write", new IOException("Broken pipe"));
        var lateWrite = new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                "Response not usable after async request completion.");

        new ApiFallbackExceptionHandler().unexpected(new IllegalStateException(disconnected),
                new MockHttpServletResponse());
        assertThat(output.getAll()).doesNotContain("ERROR");

        var answer = new ApiFallbackExceptionHandler().unexpected(new IllegalStateException(lateWrite),
                new MockHttpServletResponse());
        Object errorId = answer.getBody().getProperties().get("errorId");
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR")
                && line.contains("unexpected API failure errorId=" + errorId))).hasSize(1);
    }

    /**
     * #203 review: a server-side failure whose root cause merely reads like a disconnect (a database or outbound
     * connection reset) is a server fault; it stays at ERROR so that its errorId can be found.
     */
    @Test
    void aServerSideConnectionResetIsStillLoggedAtError(CapturedOutput output) {
        var failure = new IllegalStateException("query failed", new java.sql.SQLException("I/O error",
                new java.net.SocketException("Connection reset by peer")));

        var answer = new ApiFallbackExceptionHandler().unexpected(failure, new MockHttpServletResponse());

        Object errorId = answer.getBody().getProperties().get("errorId");
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR")
                && line.contains("unexpected API failure errorId=" + errorId))).hasSize(1);
    }

    /**
     * #207 medium: a framework client error whose status HttpStatus does not name, such as 499, keeps its status and
     * gets the documented "another 4xx" code, REQUEST_REJECTED, as the Console does; before, it became 400
     * MALFORMED_REQUEST.
     */
    @Test
    void anUnknownClientErrorKeepsItsStatusAndIsRejectedNotMalformed(CapturedOutput output) {
        var answer = new ApiFallbackExceptionHandler().unexpected(
                new org.springframework.web.ErrorResponseException(org.springframework.http.HttpStatusCode.valueOf(499)),
                new MockHttpServletResponse());

        assertThat(answer.getStatusCode().value()).isEqualTo(499);
        assertThat(answer.getBody().getStatus()).isEqualTo(499);
        assertThat(answer.getBody().getProperties()).containsEntry("code", "REQUEST_REJECTED")
                .doesNotContainKey("errorId");
        assertThat(answer.getBody().getDetail()).isEqualTo("The request was rejected.");
        assertThat(answer.getBody().getType()).hasToString("urn:dvaraos:problem:request-rejected");
        assertThat(output.getAll()).doesNotContain("ERROR");
    }

    /**
     * #207 low: after a client disconnect Spring marks the async response as failed, and a later write fails with its
     * cause-less "Response not usable after response errors." That is the disconnect, not a server fault.
     */
    @Test
    void anUnusableResponseAfterResponseErrorsIsADisconnect(CapturedOutput output) {
        var afterDisconnect = new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                "Response not usable after response errors.");

        new ApiFallbackExceptionHandler().unexpected(new IllegalStateException(afterDisconnect),
                new MockHttpServletResponse());

        assertThat(output.getAll()).doesNotContain("ERROR");
        assertThat(ApiFallbackExceptionHandler.clientDisconnected(afterDisconnect)).isTrue();
    }

    /**
     * The message recognized above is Spring's own: a response Spring marked failed after a client error on the async
     * request refuses any later use with it, without a cause.
     */
    @Test
    void springsResponseErrorsMessageIsPinned() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAsyncSupported(true);
        var async = new org.springframework.web.context.request.async.StandardServletAsyncWebRequest(request,
                new MockHttpServletResponse());
        async.startAsync();
        async.onError(new jakarta.servlet.AsyncEvent(request.getAsyncContext(), new IOException("Broken pipe")));

        var response = (jakarta.servlet.http.HttpServletResponse) async.getNativeResponse();
        org.assertj.core.api.Assertions.assertThatThrownBy(response::getOutputStream)
                .isInstanceOfSatisfying(
                        org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
                        unusable -> {
                            assertThat(unusable).hasMessage(ApiFallbackExceptionHandler.RESPONSE_FAILED).hasNoCause();
                            assertThat(ApiFallbackExceptionHandler.clientDisconnected(unusable)).isTrue();
                        });
    }

    /** Any other unexpected failure is logged once at ERROR with the errorId the client receives. */
    @Test
    void anUnexpectedFailureIsLoggedAtErrorWithItsErrorId(CapturedOutput output) {
        var answer = new ApiFallbackExceptionHandler().unexpected(new IllegalStateException("boom"),
                new MockHttpServletResponse());

        Object errorId = answer.getBody().getProperties().get("errorId");
        assertThat(output.getAll().lines().filter(line -> line.contains("ERROR")
                && line.contains("unexpected API failure errorId=" + errorId))).hasSize(1);
    }
}
