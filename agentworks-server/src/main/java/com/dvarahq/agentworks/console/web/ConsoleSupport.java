package com.dvarahq.agentworks.console.web;

import com.dvarahq.agentworks.identity.web.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Request helpers shared by the Console controllers (issue #36). */
public final class ConsoleSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The request header htmx sends on every request it makes. */
    static final String HX_REQUEST = "HX-Request";
    /** The response header that makes htmx navigate to another page. */
    static final String HX_REDIRECT = "HX-Redirect";

    private ConsoleSupport() {}

    static boolean htmx(HttpServletRequest request) {
        return "true".equalsIgnoreCase(request.getHeader(HX_REQUEST));
    }

    /**
     * After a successful action: htmx navigates to {@code path} (a full page load, so the page and its CSRF token are
     * current); a plain form submission gets 303 See Other.
     */
    static ResponseEntity<Void> redirect(HttpServletRequest request, String path) {
        if (htmx(request)) return ResponseEntity.ok().header(HX_REDIRECT, path).build();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(path)).build();
    }

    /**
     * Whether a state-changing request comes from a Console page of this server. A browser sends {@code Origin} (and
     * {@code Sec-Fetch-Site}) with every form submission and script request; a request without either is not a
     * browser's, so it cannot be a cross-site request forgery. This complements the session-bound CSRF token, and is
     * the only protection of sign-in, which has no session yet.
     */
    static boolean sameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site.toLowerCase(Locale.ROOT))) return false;
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin == null) return true;
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        String own = scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
        return origin.equalsIgnoreCase(own);
    }

    /** The {@code hx-headers} value that makes htmx send the session's CSRF token on every request. */
    static String csrfHeaders(String csrfToken) {
        return csrfToken == null ? "{}" : JSON.writeValueAsString(Map.of("X-CSRF-Token", csrfToken));
    }

    /** A UUID request parameter, or {@code null} when absent or blank; a malformed one is a Console problem. */
    static UUID uuid(String value, String name) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException malformed) {
            throw invalid("INVALID_REQUEST", name + " must be an identifier.");
        }
    }

    static ConsoleProblemException invalid(String code, String detail) {
        return new ConsoleProblemException(new ConsoleProblems.ProblemView(422, code, detail, List.of(), null, null));
    }

    /**
     * What every signed-in Console page shows about the session: the user, the selected workspace and role, the
     * user's other workspaces for switching, and the permissions that decide which actions a page offers (the
     * Console's authorization rules decide again when an action is sent).
     */
    public record ConsoleContext(String username, UUID workspaceId, String workspaceName, String role,
            Set<String> permissions, boolean organizationAdmin, List<AuthController.WorkspaceView> workspaces,
            String csrfHeaders) {
        public boolean can(String permission) {
            return permissions.contains(permission);
        }
    }
}
