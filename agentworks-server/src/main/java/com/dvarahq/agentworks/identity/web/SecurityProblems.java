package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * RFC 9457 problems for authentication and authorization failures raised before a controller runs. Responses use
 * fixed codes and text: an unknown user, a wrong password, and an expired, revoked, tampered, or unknown token or
 * session are indistinguishable.
 */
public final class SecurityProblems implements AuthenticationEntryPoint, AccessDeniedHandler {
    public static final String AUTHENTICATION_REQUIRED = "AUTHENTICATION_REQUIRED";
    public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";
    public static final String WORKSPACE_SCOPE_UNAVAILABLE = "WORKSPACE_SCOPE_UNAVAILABLE";
    public static final String CSRF_TOKEN_INVALID = "CSRF_TOKEN_INVALID";
    public static final String CREDENTIAL_IN_URL = "CREDENTIAL_IN_URL";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        boolean presented = exception instanceof BadCredentialsException;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"agentworks\"");
        write(response, HttpServletResponse.SC_UNAUTHORIZED, presented ? AUTHENTICATION_FAILED : AUTHENTICATION_REQUIRED,
                presented ? "The credentials are not valid." : "Authentication is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof AgentWorksAuthentication authentication) {
            AuthenticatedPrincipal principal = authentication.getPrincipal();
            if (principal.mustChangePassword()) {
                write(response, HttpServletResponse.SC_FORBIDDEN, PASSWORD_CHANGE_REQUIRED,
                        "The password must be changed before any other operation.");
                return;
            }
            if (principal.workspaceId() == null) {
                write(response, HttpServletResponse.SC_FORBIDDEN, WORKSPACE_SCOPE_UNAVAILABLE,
                        "no authorized workspace scope is available for this request");
                return;
            }
        }
        write(response, HttpServletResponse.SC_FORBIDDEN, ACCESS_DENIED, "The principal may not perform this operation.");
    }

    /** Writes a problem document with a fixed code and detail; neither may carry request content. */
    public static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
        if (response.isCommitted()) return;
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String type = "urn:dvaraos:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-');
        response.getWriter().write("{\"type\":\"" + type + "\",\"status\":" + status + ",\"detail\":\"" + detail
                + "\",\"code\":\"" + code + "\"}");
        response.flushBuffer();
    }
}
