package com.dvarahq.agentworks.console.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How the Console reads the problem the API's handlers answered (issue #36). */
@ExtendWith(OutputCaptureExtension.class)
@Isolated("captures process-wide logging while Spring application contexts can reconfigure the logging system")
class ConsoleProblemsTest {

    @Test
    void aResolvedProblemKeepsTheApisStatusCodeDetailAndRetryAfter() {
        var problem = ConsoleProblems.ProblemView.parse(503, ("{\"code\":\"SERVICE_UNAVAILABLE\",\"detail\":\"Retry.\","
                + "\"errorId\":\"e1\"}").getBytes(StandardCharsets.UTF_8), "3");
        assertThat(problem.status()).isEqualTo(503);
        assertThat(problem.code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(problem.detail()).isEqualTo("Retry.");
        assertThat(problem.errorId()).isEqualTo("e1");
        assertThat(problem.retryAfter()).isEqualTo("3");
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * #195: a failure no API handler resolves, or resolves to no error status, is logged once with a new errorId and
     * answered as an internal error carrying that id, never a success and never without a log line. Being a problem,
     * it still lets a page that degrades on a refused read degrade.
     */
    @Test
    void aFailureNoHandlerResolvesIsLoggedOnceWithAnErrorIdTheConsoleShows(CapturedOutput output) {
        for (Class<?> advice : List.of(Api.class, SuccessAdvice.class)) {
            ConsoleProblems problems = problems(Api.class, advice);
            IllegalStateException failure = new IllegalStateException("unresolved-" + advice.getSimpleName());

            var problem = problems.resolve(request(), Api.class, failure);

            assertThat(problem.status()).as(advice.getSimpleName()).isEqualTo(500);
            assertThat(problem.code()).isEqualTo("INTERNAL_ERROR");
            assertThat(problem.errorId()).isNotBlank();
            assertThat(output.getAll().lines().filter(line -> line.contains("errorId=" + problem.errorId())))
                    .hasSize(1);
            assertThat(output.getAll()).contains("unresolved-" + advice.getSimpleName());
        }
    }

    /**
     * #200: a failure the API's fallback handler resolves is logged by that handler alone, with its own errorId. The
     * Console resolves into a captured response that is never committed, so the fallback always answers with its
     * problem body and the Console never logs the failure a second time.
     */
    @Test
    void aFailureTheFallbackResolvesIsLoggedOnlyByTheFallback(CapturedOutput output) {
        ConsoleProblems problems = problems(Api.class, com.dvarahq.agentworks.api.web.ApiFallbackExceptionHandler.class);
        IllegalStateException failure = new IllegalStateException("resolved-by-the-fallback");

        var problem = problems.resolve(request(), Api.class, failure);

        assertThat(problem.status()).isEqualTo(500);
        assertThat(problem.errorId()).isNotBlank();
        assertThat(output.getAll().lines().filter(line -> line.contains("errorId="))).hasSize(1)
                .allMatch(line -> line.contains("unexpected API failure errorId=" + problem.errorId()));
    }

    /**
     * #203: a handler's error status without a body keeps that status and its Retry-After, with the status's generic
     * code and text, as the API answers it; it is no internal error, and nothing is logged for it.
     */
    @Test
    void anErrorStatusWithoutABodyKeepsItsStatusAndRetryAfter(CapturedOutput output) {
        ConsoleProblems problems = problems(Api.class, EmptyErrorAdvice.class);

        var problem = problems.resolve(request(), Api.class, new IllegalStateException("throttled"));

        assertThat(problem.status()).isEqualTo(429);
        assertThat(problem.code()).isEqualTo("REQUEST_REJECTED");
        assertThat(problem.detail()).isEqualTo("The request was rejected.");
        assertThat(problem.retryAfter()).isEqualTo("7");
        assertThat(problem.errorId()).isNull();
        assertThat(output.getAll()).doesNotContain("errorId=");
    }

    /** #203 review: an unknown client error without a body gets the API's 4xx default code and text. */
    @Test
    void anUnknownClientErrorWithoutABodyGetsTheApisClientErrorDefault() {
        var problem = ConsoleProblems.ProblemView.generic(499, null);

        assertThat(problem.status()).isEqualTo(499);
        assertThat(problem.code()).isEqualTo("REQUEST_REJECTED");
        assertThat(problem.detail()).isEqualTo("The request was rejected.");
        // #207: the REST API answers an unknown 4xx from the same ApiProblems default.
        var api = com.dvarahq.agentworks.api.web.ApiProblems.problem(
                org.springframework.http.HttpStatusCode.valueOf(499));
        assertThat(api.getStatus()).isEqualTo(problem.status());
        assertThat(api.getProperties()).containsEntry("code", problem.code());
        assertThat(api.getDetail()).isEqualTo(problem.detail());
    }

    /**
     * #207 review: every Console fallback takes its status's generic code and text, so a 5xx is never a rejected
     * request and a 4xx never an internal error: a problem body without a code or text, a problem response without a
     * code, and an error response without a problem body.
     */
    @Test
    void everyFallbackTakesItsStatusesGenericCodeAndText() {
        byte[] empty = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var codeless = org.springframework.http.ProblemDetail.forStatus(400);
        for (int status : new int[]{400, 409, 429, 499, 500, 502, 503}) {
            String code = com.dvarahq.agentworks.api.web.ApiProblems.code(status);
            String detail = com.dvarahq.agentworks.api.web.ApiProblems.detail(status);
            for (var view : List.of(ConsoleProblems.ProblemView.parse(status, empty, null),
                    ConsoleProblems.ProblemView.of(status, codeless, null),
                    ConsoleProblems.fromResponse(org.springframework.http.ResponseEntity.status(status).build())
                            .problem())) {
                assertThat(view.status()).as("%d", status).isEqualTo(status);
                assertThat(view.code()).as("%d", status).isEqualTo(code);
                assertThat(view.detail()).as("%d", status).isEqualTo(detail);
            }
            if (status < 500) {
                assertThat(code).as("%d", status).isNotEqualTo("INTERNAL_ERROR");
            } else {
                assertThat(code).as("%d", status).isNotEqualTo("REQUEST_REJECTED");
            }
        }
        assertThat(ConsoleProblems.ProblemView.parse(502, empty, null).detail())
                .isEqualTo("An unexpected error occurred.");
        assertThat(ConsoleProblems.ProblemView.parse(409, empty, null).detail()).isEqualTo("The request was rejected.");
        assertThat(ConsoleProblems.fromResponse(org.springframework.http.ResponseEntity.status(503)
                .header("Retry-After", "4").build()).problem().retryAfter()).isEqualTo("4");
    }

    /** #203 review: a server error other than 503 without a body keeps its status and is logged once with an errorId. */
    @Test
    void aServerErrorWithoutABodyIsLoggedOnceWithAnErrorId(CapturedOutput output) {
        ConsoleProblems problems = problems(Api.class, EmptyServerErrorAdvice.class);

        var problem = problems.resolve(request(), Api.class, new IllegalStateException("gateway-down"));

        assertThat(problem.status()).isEqualTo(502);
        assertThat(problem.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.errorId()).isNotBlank();
        assertThat(output.getAll().lines().filter(line -> line.contains("errorId=" + problem.errorId()))).hasSize(1);
    }

    /** Issue #210: a body-less 5xx returned directly is handled like one captured from an exception handler. */
    @Test
    void aServerErrorResponseWithoutABodyIsLoggedOnceWithAnErrorId(CapturedOutput output) {
        var problem = ConsoleProblems.fromResponse(ResponseEntity.status(502).build()).problem();

        assertThat(problem.status()).isEqualTo(502);
        assertThat(problem.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.errorId()).isNotBlank();
        assertThat(output.getAll().lines().filter(line -> line.contains("errorId=" + problem.errorId()))).hasSize(1);
    }

    /**
     * #200, #203: a client that disconnected is no server fault; it is not logged at ERROR, whether the API's fallback
     * handler resolves it (as it resolves every failure no other handler does) or nothing does.
     */
    @Test
    void aClientDisconnectIsNotLoggedAsAnError(CapturedOutput output) {
        for (Class<?>[] beans : List.of(new Class<?>[]{Api.class,
                com.dvarahq.agentworks.api.web.ApiFallbackExceptionHandler.class}, new Class<?>[]{Api.class})) {
            ConsoleProblems problems = problems(beans);
            var failure = new java.io.UncheckedIOException(
                    new org.apache.catalina.connector.ClientAbortException(new java.io.IOException("Broken pipe")));

            var problem = problems.resolve(request(), Api.class, failure);

            assertThat(problem.status()).isEqualTo(500);
            assertThat(problem.errorId()).isNotBlank();
        }
        assertThat(output.getAll()).doesNotContain("ERROR");
    }

    /** A failure the API's handlers resolve is still the API's own problem. */
    @Test
    void aFailureTheApiResolvesIsTheApisProblem() {
        ConsoleProblems problems = problems(Api.class, ApiAdvice.class);

        var problem = problems.resolve(request(), Api.class, new IllegalArgumentException("refused"));

        assertThat(problem.status()).isEqualTo(409);
        assertThat(problem.code()).isEqualTo("REFUSED");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/console/agents");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, new MockHttpServletResponse()));
        return request;
    }

    private static ConsoleProblems problems(Class<?>... beans) {
        GenericApplicationContext context = new GenericApplicationContext();
        for (Class<?> bean : beans) context.registerBean(bean);
        context.registerBean(RequestMappingHandlerAdapter.class, () -> {
            RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
            adapter.setMessageConverters(List.of(new org.springframework.http.converter.StringHttpMessageConverter(),
                    new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter()));
            return adapter;
        });
        context.refresh();
        return new ConsoleProblems(context, context.getBeanProvider(RequestMappingHandlerAdapter.class));
    }

    @RestController
    static class Api {
        @GetMapping("/api")
        public String get() {
            return "";
        }
    }

    @RestControllerAdvice
    static class SuccessAdvice {
        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<String> ok(IllegalStateException failure) {
            return ResponseEntity.ok("{}");
        }
    }

    @RestControllerAdvice
    static class EmptyErrorAdvice {
        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<Void> empty(IllegalStateException failure) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7").build();
        }
    }

    @RestControllerAdvice
    static class EmptyServerErrorAdvice {
        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<Void> empty(IllegalStateException failure) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    @RestControllerAdvice
    static class ApiAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        ResponseEntity<String> refused(IllegalArgumentException refused) {
            return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body("{\"status\":409,\"code\":\"REFUSED\",\"detail\":\"Refused.\"}");
        }
    }
}
