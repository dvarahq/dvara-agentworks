package com.dvarahq.agentworks.console.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Refuses a state-changing Console request that a browser sent from another site (issue #36), before any handler
 * runs. The session-bound CSRF token already refuses such a request; this is defense in depth that does not depend
 * on the token. Sign-in, which has no token, checks the same rule itself so that it can answer with its own page.
 */
public final class ConsoleOriginInterceptor implements HandlerInterceptor {
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        if (SAFE.contains(request.getMethod()) || ConsoleSupport.sameOrigin(request)) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("<div class=\"alert alert-danger\" role=\"alert\"><strong>CROSS_ORIGIN_REQUEST</strong>"
                + " This action must be sent from this server's own Console page.</div>");
        return false;
    }
}
