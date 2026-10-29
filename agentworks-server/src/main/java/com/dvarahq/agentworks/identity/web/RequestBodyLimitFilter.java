package com.dvarahq.agentworks.identity.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Bounds ordinary request bodies before they can consume unbounded heap. Both Content-Length and bytes actually read
 * are checked, so a chunked request cannot bypass the limit. The artifact upload route is excluded because its store
 * requires Content-Length and applies the separately configured artifact maximum while streaming to durable storage.
 */
public final class RequestBodyLimitFilter extends OncePerRequestFilter {
    private static final Set<String> BODY_METHODS = Set.of(HttpMethod.POST.name(), HttpMethod.PUT.name(),
            HttpMethod.PATCH.name());
    private final long maximum;

    public RequestBodyLimitFilter(long maximum) {
        if (maximum < 1) throw new IllegalArgumentException("maximum must be positive");
        this.maximum = maximum;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !BODY_METHODS.contains(request.getMethod())
                || (HttpMethod.POST.matches(request.getMethod())
                && "/api/v1/artifacts".equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maximum) {
            refuse(response);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request, maximum), response);
        } catch (BodyTooLarge failure) {
            if (response.isCommitted()) throw failure;
            response.reset();
            refuse(response);
        }
    }

    private static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Payload Too Large\",\"status\":413,"
                + "\"detail\":\"The request body exceeds the configured limit.\","
                + "\"code\":\"PAYLOAD_TOO_LARGE\"}");
    }

    /** Used by the MVC error mapper when its message converter wraps the stream exception. */
    public static boolean causedByLimit(Throwable failure) {
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof BodyTooLarge) return true;
        }
        return false;
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maximum;
        private ServletInputStream stream;

        private LimitedRequest(HttpServletRequest request, long maximum) {
            super(request);
            this.maximum = maximum;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new LimitedInputStream(super.getInputStream(), maximum);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maximum;
        private long read;

        private LimitedInputStream(ServletInputStream delegate, long maximum) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) counted(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = delegate.read(bytes, offset, length);
            if (count > 0) counted(count);
            return count;
        }

        private void counted(int count) {
            read += count;
            if (read > maximum) throw new BodyTooLarge();
        }
    }

    private static final class BodyTooLarge extends RuntimeException {
        private BodyTooLarge() { super("request body limit exceeded", null, false, false); }
    }
}
