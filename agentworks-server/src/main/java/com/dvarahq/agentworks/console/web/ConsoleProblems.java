package com.dvarahq.agentworks.console.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import com.dvarahq.agentworks.api.web.ApiFallbackExceptionHandler;
import com.dvarahq.agentworks.api.web.ApiProblems;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns a refusal of an API call made by the Console into the problem the API itself answers for it (issue #36).
 * It runs Spring MVC's exception-handler resolution for the REST controller that was called, with the same
 * controller-scoped and fallback handlers, into a captured response, so the Console shows exactly the API's status,
 * stable {@code code}, fixed {@code detail}, diagnostics, and {@code errorId}; nothing it shows is made up here.
 */
public final class ConsoleProblems {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConsoleProblems.class);

    private final ApplicationContext context;
    private final ObjectProvider<RequestMappingHandlerAdapter> adapter;
    private volatile ExceptionHandlerExceptionResolver resolver;

    /**
     * @param adapter supplies the API's message converters; it is looked up on first use, since the handler adapter
     *     itself creates the Console's controller advice, which needs this
     */
    public ConsoleProblems(ApplicationContext context, ObjectProvider<RequestMappingHandlerAdapter> adapter) {
        this.context = Objects.requireNonNull(context, "context");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    /**
     * The problem the API answers when {@code controller} refuses a request with {@code failure}. A failure that no
     * handler resolves to an error status (no handler, or no error status) is logged here, once, with a new
     * {@code errorId}, and answered as 500 {@code INTERNAL_ERROR} carrying that id (#195): the Console shows the id,
     * and a page that degrades on a refused read still degrades. A failure the API's handlers resolved was logged by
     * them if it had to be, and keeps their {@code errorId}. A handler's error status without a body keeps that status
     * and its {@code Retry-After}, with the status's generic code and text (#203), as the API itself answers it; a
     * server error other than 503 among them is also logged once here with a new {@code errorId} that it carries.
     */
    public ProblemView resolve(HttpServletRequest request, Class<?> controller, RuntimeException failure) {
        HttpServletResponse current = currentResponse();
        CapturedResponse captured = new CapturedResponse(current);
        Object bean = context.getBean(controller);
        boolean resolved = resolver().resolveException(request, captured,
                new HandlerMethod(bean, anyHandlerMethod(controller)), failure) != null;
        byte[] body = captured.body();
        if (!resolved || captured.getStatus() < 400) {
            String errorId = UUID.randomUUID().toString();
            ApiFallbackExceptionHandler.logUnexpected(LOG, "unresolved Console API failure", errorId, failure);
            return new ProblemView(500, "INTERNAL_ERROR", "An unexpected error occurred.", List.of(), errorId, null);
        }
        String retryAfter = captured.getHeader("Retry-After");
        if (body.length == 0) {
            int status = captured.getStatus();
            ProblemView generic = ProblemView.generic(status, retryAfter);
            // A server error without a body carries no errorId of the handler's; it gets one here, logged once.
            if (status < 500 || status == 503) return generic;
            String errorId = UUID.randomUUID().toString();
            ApiFallbackExceptionHandler.logUnexpected(LOG, "Console API failure answered without a body", errorId,
                    failure);
            return new ProblemView(status, generic.code(), generic.detail(), List.of(), errorId, retryAfter);
        }
        return ProblemView.parse(captured.getStatus(), body, retryAfter);
    }

    private ExceptionHandlerExceptionResolver resolver() {
        ExceptionHandlerExceptionResolver current = resolver;
        if (current != null) return current;
        synchronized (this) {
            if (resolver == null) {
                ExceptionHandlerExceptionResolver created = new ExceptionHandlerExceptionResolver();
                created.setApplicationContext(context);
                created.setMessageConverters(adapter.getObject().getMessageConverters());
                created.afterPropertiesSet();
                resolver = created;
            }
            return resolver;
        }
    }

    /** A problem the API answered as a response rather than an exception, such as a throttled sign-in. */
    static ConsoleProblemException fromResponse(ResponseEntity<?> answer) {
        if (answer.getBody() instanceof ProblemDetail problem) {
            return new ConsoleProblemException(ProblemView.of(answer.getStatusCode().value(), problem,
                    answer.getHeaders().getFirst("Retry-After")));
        }
        int status = answer.getStatusCode().value();
        ProblemView generic = ProblemView.generic(status, answer.getHeaders().getFirst("Retry-After"));
        if (status < 500 || status == 503) return new ConsoleProblemException(generic);
        String errorId = UUID.randomUUID().toString();
        LOG.error("Console API response had no problem body (status {}) errorId={}", status, errorId);
        return new ConsoleProblemException(new ProblemView(status, generic.code(), generic.detail(), List.of(),
                errorId, generic.retryAfter()));
    }

    private static HttpServletResponse currentResponse() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                && attributes.getResponse() != null) {
            return attributes.getResponse();
        }
        throw new IllegalStateException("the Console resolves problems only within a request");
    }

    private static Method anyHandlerMethod(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods()).filter(method -> Modifier.isPublic(method.getModifiers()))
                .findFirst().orElseThrow(() -> new IllegalStateException("no handler method on " + controller));
    }

    /**
     * An API problem as the Console shows it: every field is server text or a stable code, and is always rendered
     * escaped. {@code diagnostics} are the AgentSpec diagnostics of a refused publication.
     */
    public record ProblemView(int status, String code, String detail, List<String> diagnostics, String errorId,
            String retryAfter) {
        /** An error problem ({@code status} at least 400) from the API's non-empty problem body. */
        static ProblemView parse(int status, byte[] body, String retryAfter) {
            JsonNode json;
            try {
                json = JSON.readTree(body);
            } catch (RuntimeException unreadable) {
                json = JSON.missingNode();
            }
            List<String> diagnostics = new ArrayList<>();
            for (JsonNode diagnostic : json.path("diagnostics")) {
                diagnostics.add(diagnostic.path("path").asString("") + ": " + diagnostic.path("message").asString("")
                        + " (" + diagnostic.path("code").asString("") + ")");
            }
            // A body without a code or text gets its status's generic ones, so a 4xx is never an internal error and a
            // 5xx never a rejected request (#207 review).
            return new ProblemView(status, json.path("code").asString(ApiProblems.code(status)),
                    json.path("detail").asString(ApiProblems.detail(status)), List.copyOf(diagnostics),
                    json.path("errorId").isString() ? json.path("errorId").asString() : null, retryAfter);
        }

        /** An error status answered without a body: the status's generic code and text, as the API describes it. */
        static ProblemView generic(int status, String retryAfter) {
            // An unknown client error gets the API's own 4xx default, never a specific code such as 400's (#207).
            return new ProblemView(status, ApiProblems.code(status), ApiProblems.detail(status), List.of(), null,
                    retryAfter);
        }

        static ProblemView of(int status, ProblemDetail problem, String retryAfter) {
            Map<String, Object> properties = problem.getProperties() == null ? Map.of() : problem.getProperties();
            Object code = properties.get("code");
            return new ProblemView(status, code == null ? ApiProblems.code(status) : code.toString(),
                    problem.getDetail() == null ? ApiProblems.detail(status) : problem.getDetail(), List.of(), null,
                    retryAfter);
        }
    }

    /** Captures what an exception handler writes, and writes nothing to the real response. */
    private static final class CapturedResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private int status = 200;
        private String contentType;
        private PrintWriter writer;

        private CapturedResponse(HttpServletResponse response) {
            super(response);
        }

        byte[] body() {
            if (writer != null) writer.flush();
            return body.toByteArray();
        }

        @Override
        public void setStatus(int status) {
            this.status = status;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void sendError(int status) {
            this.status = status;
        }

        @Override
        public void sendError(int status, String message) {
            this.status = status;
        }

        @Override
        public void setHeader(String name, String value) {
            headers.put(name, new ArrayList<>(List.of(value)));
        }

        @Override
        public void addHeader(String name, String value) {
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }

        @Override
        public void setIntHeader(String name, int value) {
            setHeader(name, Integer.toString(value));
        }

        @Override
        public void addIntHeader(String name, int value) {
            addHeader(name, Integer.toString(value));
        }

        @Override
        public void setDateHeader(String name, long date) {
            setHeader(name, Long.toString(date));
        }

        @Override
        public void addDateHeader(String name, long date) {
            addHeader(name, Long.toString(date));
        }

        @Override
        public boolean containsHeader(String name) {
            return headers.containsKey(name);
        }

        @Override
        public String getHeader(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        @Override
        public Collection<String> getHeaders(String name) {
            return headers.getOrDefault(name, List.of());
        }

        @Override
        public Collection<String> getHeaderNames() {
            return headers.keySet();
        }

        @Override
        public void addCookie(jakarta.servlet.http.Cookie cookie) {
            // A problem never sets a cookie on the Console's response.
        }

        @Override
        public void setContentType(String type) {
            this.contentType = type;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void setCharacterEncoding(String encoding) {
            // The captured body is read as UTF-8 JSON.
        }

        @Override
        public void setContentLength(int length) {}

        @Override
        public void setContentLengthLong(long length) {}

        @Override
        public boolean isCommitted() {
            return false;
        }

        @Override
        public void reset() {
            body.reset();
            headers.clear();
            status = 200;
        }

        @Override
        public void resetBuffer() {
            body.reset();
        }

        @Override
        public void flushBuffer() {}

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public void write(int value) {
                    body.write(value);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) {
                    body.write(bytes, offset, length);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
            };
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) writer = new PrintWriter(body, false, StandardCharsets.UTF_8);
            return writer;
        }
    }
}
