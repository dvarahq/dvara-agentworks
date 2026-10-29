package com.dvarahq.agentworks.runplane.admission.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Lets a run event stream use its response only after the request thread has left the whole filter chain (#192).
 *
 * <p>A stream writes on its own virtual thread. Filters still run on the request thread after the controller returns,
 * and some write to the response there: Spring Security's {@code HeaderWriterFilter} writes its headers after the
 * chain. When the stream's thread wrote, failed on a client that had already disconnected, and completed the response
 * in that window, the two threads used the same response at once, and the request thread failed inside Tomcat's
 * header map. This filter is registered first, outside every application filter that acts after the chain (only
 * Boot's character-encoding filter shares its order, and it acts only before the chain): the controller hands it the
 * stream's release, and it runs it once the chain has returned, so no application filter uses the response while the
 * stream does. It acts only on stream requests ({@code GET .../stream}), on their original dispatch; every other
 * request passes straight through. The stream controller refuses a request this filter does not wrap
 * ({@link #require}), so the race cannot return silently.
 *
 * <p>When the request did not go asynchronous (the response failed before the stream could start), or the chain
 * failed, the container ends the response and the stream is cancelled before it starts.
 *
 * <p><b>Tomcat's own valves.</b> After the filter chain, the request thread still returns through Tomcat's valves
 * ({@code StandardWrapperValve}, {@code StandardHostValve}, {@code ErrorReportValve}, the access log) and
 * {@code CoyoteAdapter}. No servlet API reports when that thread has left the container. This window is the one the
 * Servlet specification allows for every asynchronous request (another thread may write as soon as
 * {@code startAsync} returns), and Tomcat 11 is built for it: those valves skip error handling for a request that is
 * asynchronous, {@code ErrorReportValve} acts on a committed response only when an error was reported and then only
 * while I/O is still allowed (a failed stream write disallows it), and the access log only reads. A completion by the
 * stream meanwhile is deferred by Tomcat's asynchronous state machine until the container thread returns.
 */
public final class RunStreamReleaseFilter extends OncePerRequestFilter {
    private static final String PENDING = RunStreamReleaseFilter.class.getName() + ".PENDING";

    /**
     * The release a request hands over; one per request, set and run only on the request thread. It is told whether
     * the request went asynchronous and its chain returned.
     */
    static final class Pending {
        private Consumer<Boolean> release;

        void hold(Consumer<Boolean> release) {
            this.release = release;
        }

        private void release(boolean async) {
            Consumer<Boolean> held = release;
            release = null;
            if (held != null) held.accept(async);
        }
    }

    /** The request's release point when this filter wraps it, or {@code null} when the request is not filtered. */
    static Pending pending(ServletRequest request) {
        return request.getAttribute(PENDING) instanceof Pending pending ? pending : null;
    }

    /** The request's release point; fails when this filter does not wrap the request. */
    static Pending require(ServletRequest request) {
        Pending pending = pending(request);
        if (pending == null) {
            throw new IllegalStateException("run event streams need the RunStreamReleaseFilter around the filter chain");
        }
        return pending;
    }

    /**
     * Only a stream request is wrapped. The path is the decoded one the dispatcher servlet maps (servlet path plus path
     * info), not the raw request URI, so an encoded spelling such as {@code %73tream} is wrapped too (#200).
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return !"GET".equals(request.getMethod()) || !path.endsWith("/stream");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Pending pending = new Pending();
        request.setAttribute(PENDING, pending);
        boolean returned = false;
        try {
            chain.doFilter(request, response);
            returned = true;
        } finally {
            request.removeAttribute(PENDING);
            // A chain that failed after the request went asynchronous leaves the response to the container's error
            // handling: the stream is cancelled rather than started.
            pending.release(returned && request.isAsyncStarted());
        }
    }
}
