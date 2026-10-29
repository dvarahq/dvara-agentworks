package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.identity.SecurityPolicy;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The session cookie: {@code HttpOnly}, {@code Path=/}, no {@code Domain}, {@code SameSite=Strict} by default,
 * and under the production default {@code Secure} with the {@code __Host-} prefix so that no subdomain or plain
 * HTTP response can set or overwrite it. Its lifetime is the remaining lifetime of the session it carries.
 */
public final class SessionCookies {
    public static final String CSRF_HEADER = "X-CSRF-Token";

    private final SecurityPolicy.Session policy;

    public SessionCookies(SecurityPolicy.Session policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public String name() {
        return policy.cookieName();
    }

    /** The session value, or {@code null} when absent or ambiguous (several cookies of the same name). */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        String value = null;
        for (Cookie cookie : cookies) {
            if (!name().equals(cookie.getName())) continue;
            if (value != null) return null;
            value = cookie.getValue();
        }
        return value;
    }

    /** Sets the cookie to live exactly as long as the session it carries. */
    public void write(HttpServletResponse response, String value, Instant expiresAt, Instant now) {
        // Rounded up to whole seconds, so the cookie never expires before the session does.
        long millis = Math.max(0, Duration.between(now, expiresAt).toMillis());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(value, Duration.ofSeconds((millis + 999) / 1000)).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(name(), value).httpOnly(true).secure(policy.cookieSecure()).path("/")
                .sameSite(policy.sameSite()).maxAge(maxAge).build();
    }
}
