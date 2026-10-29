package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.identity.CredentialCodec;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/**
 * Authenticates every request from exactly one credential, statelessly against the database:
 * <ol>
 *   <li>A request whose URL carries anything shaped like an AgentWorks credential is refused, so credentials never
 *       reach access logs, browser history, or referrers (this includes SSE reconnects, which must use the cookie
 *       or a header).</li>
 *   <li>An {@code Authorization} header must be a valid {@code Bearer} API token; otherwise the request fails with
 *       401 and no other credential is consulted.</li>
 *   <li>Otherwise a session cookie authenticates the request. A state-changing request authenticated by the cookie
 *       must also carry the session's CSRF token in {@value SessionCookies#CSRF_HEADER}. An invalid or expired
 *       cookie is cleared and the request continues unauthenticated, so public endpoints still work.</li>
 * </ol>
 * Sign-in and bootstrap ignore any presented cookie; sign-in revokes it and issues a new one.
 */
public final class CredentialAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AuthenticationManager authentication;
    private final SessionCookies cookies;
    private final SecurityProblems problems;
    private final RequestMatcher cookieless;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository contexts = new RequestAttributeSecurityContextRepository();

    public CredentialAuthenticationFilter(AuthenticationManager authentication, SessionCookies cookies,
            SecurityProblems problems, RequestMatcher cookieless) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.cookies = Objects.requireNonNull(cookies, "cookies");
        this.problems = Objects.requireNonNull(problems, "problems");
        this.cookieless = Objects.requireNonNull(cookieless, "cookieless");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (credentialInUrl(request)) {
            SecurityProblems.write(response, HttpServletResponse.SC_BAD_REQUEST, SecurityProblems.CREDENTIAL_IN_URL,
                    "Credentials must be sent in a header or cookie, never in the URL.");
            return;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            try {
                authenticated(authentication.authenticate(CredentialAuthenticationToken.bearer(bearer(authorization))),
                        request, response);
            } catch (AuthenticationException failed) {
                holder.clearContext();
                problems.commence(request, response, failed);
                return;
            }
        } else if (!cookieless.matches(request)) {
            String session = cookies.read(request);
            if (session != null) {
                Authentication result;
                try {
                    result = authentication.authenticate(CredentialAuthenticationToken.session(session));
                } catch (AuthenticationException stale) {
                    cookies.clear(response);
                    result = null;
                }
                if (result != null) {
                    if (!SAFE_METHODS.contains(request.getMethod()) && !CredentialCodec.sameToken(
                            CredentialCodec.csrfToken(session), request.getHeader(SessionCookies.CSRF_HEADER))) {
                        SecurityProblems.write(response, HttpServletResponse.SC_FORBIDDEN,
                                SecurityProblems.CSRF_TOKEN_INVALID,
                                "A state-changing request authenticated by a session cookie needs its CSRF token.");
                        return;
                    }
                    authenticated(result, request, response);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticated(Authentication result, HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(result);
        holder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    private static String bearer(String authorization) {
        String value = authorization.strip();
        if (value.length() < 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return "";
        return value.substring(7).strip();
    }

    private static boolean credentialInUrl(HttpServletRequest request) {
        return carriesCredential(request.getRequestURI()) || carriesCredential(request.getQueryString());
    }

    private static boolean carriesCredential(String part) {
        if (part == null || part.isEmpty()) return false;
        if (CredentialCodec.CREDENTIAL_SHAPE.matcher(part).find()) return true;
        try {
            return CredentialCodec.CREDENTIAL_SHAPE.matcher(URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8)).find();
        } catch (IllegalArgumentException malformed) {
            // Not decodable, so it cannot carry a decoded credential; the raw form was already checked.
            return false;
        }
    }
}
