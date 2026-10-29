package com.dvarahq.agentworks.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How the security chain answers the Web Console (issue #36), a browser page rather than an API client: sign-in
 * instead of a 401 problem, a password change when one is pending, an HTML page instead of a 403 problem, and a
 * Content-Security-Policy that admits only this server's own scripts and styles.
 */
public final class ConsoleAccess implements AuthenticationEntryPoint, AccessDeniedHandler {
    /** The Console's pages, actions, and assets. */
    public static final RequestMatcher CONSOLE = PathPatternRequestMatcher.withDefaults().matcher("/console/**");

    /**
     * No inline script, style, or event handler; scripts, styles, and fonts only from this server (vendored, with
     * pinned subresource integrity); images from this server or Bootstrap's inline {@code data:} icons; requests
     * (htmx, the event stream) only to this server; no framing, plugins, base rewriting, or foreign form targets.
     */
    public static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    private static final String HX_REQUEST = "HX-Request";
    private static final String HX_REDIRECT = "HX-Redirect";

    /** An anonymous (or signed-out) request: to sign-in, whether a page load or an htmx action. */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        redirect(request, response, "/console/login", HttpServletResponse.SC_UNAUTHORIZED);
    }

    /**
     * A signed-in request the rules refuse: a session with a pending password change goes to the password page;
     * anything else gets a fixed HTML page (403), never a hint of what exists.
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> authorities = authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        if (authorities.contains(AgentWorksAuthentication.SESSION)
                && !authorities.contains(AgentWorksAuthentication.ACTIVE_SESSION)) {
            redirect(request, response, "/console/password", HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        boolean fragment = "true".equalsIgnoreCase(request.getHeader(HX_REQUEST));
        response.getWriter().write(fragment
                ? "<div class=\"alert alert-danger\" role=\"alert\"><strong>ACCESS_DENIED</strong> Your role may not"
                        + " perform this action.</div>"
                : "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Access denied · Dvara AgentWorks"
                        + "</title><link rel=\"stylesheet\" href=\"/console/assets/console.css\"></head><body><main>"
                        + "<h1>Access denied</h1><p>Your role may not open this page. <a href=\"/console/runs\">Back to"
                        + " runs</a> · <a href=\"/console/login\">Sign in as someone else</a></p></main></body></html>");
    }

    private static void redirect(HttpServletRequest request, HttpServletResponse response, String path, int htmxStatus)
            throws IOException {
        if ("true".equalsIgnoreCase(request.getHeader(HX_REQUEST))) {
            // htmx follows HX-Redirect with a full page load.
            response.setStatus(htmxStatus);
            response.setHeader(HX_REDIRECT, path);
            return;
        }
        response.sendRedirect(path);
    }
}
