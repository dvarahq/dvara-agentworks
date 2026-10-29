package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.api.web.Page;
import com.dvarahq.agentworks.domain.identity.AuthenticatedPrincipal;
import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.identity.IdentityException;
import com.dvarahq.agentworks.identity.IdentityService;
import com.dvarahq.agentworks.identity.InstallationBootstrapService;
import com.dvarahq.agentworks.identity.InstallationBootstrapService.Completed;
import com.dvarahq.agentworks.identity.MembershipService;
import com.dvarahq.agentworks.identity.SessionService;
import com.dvarahq.agentworks.identity.SessionService.EstablishedSession;
import com.dvarahq.agentworks.identity.CredentialCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Sign-in, sign-out, session inspection and rotation, password change, and the one-time installation bootstrap.
 * Request records never print passwords or tokens.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public final class AuthController {
    public record LoginRequest(String username, String password, UUID workspaceId) {
        @Override
        public String toString() {
            return "LoginRequest[workspaceId=" + workspaceId + "]";
        }
    }

    public record BootstrapRequest(String token, String username, String displayName, String password,
            String organizationName, String workspaceName) {
        @Override
        public String toString() {
            return "BootstrapRequest[]";
        }
    }

    public record PasswordChange(String currentPassword, String newPassword) {
        @Override
        public String toString() {
            return "PasswordChange[]";
        }
    }

    public record WorkspaceSelection(UUID workspaceId) {}

    /** The authenticated principal; {@code csrfToken} is present only for a browser session. */
    public record SessionView(UUID userId, String username, UUID organizationId, UUID workspaceId, String role,
            List<String> permissions, boolean organizationAdmin, String authenticationMethod, UUID credentialId,
            boolean mustChangePassword, Instant expiresAt, String csrfToken) {
        static SessionView of(AuthenticatedPrincipal principal, String csrfToken, Instant expiresAt) {
            return new SessionView(principal.subjectId(), principal.username(), principal.organizationId().value(),
                    principal.workspace().map(workspace -> workspace.value()).orElse(null),
                    principal.role() == null ? null : principal.role().name(),
                    principal.mustChangePassword() ? List.of()
                            : principal.permissions().stream().map(Enum::name).sorted().toList(),
                    principal.organizationAdmin(), principal.method().value(), principal.credentialId(),
                    principal.mustChangePassword(), expiresAt, csrfToken);
        }

        @Override
        public String toString() {
            return "SessionView[userId=" + userId + ", workspaceId=" + workspaceId + "]";
        }
    }

    public record WorkspaceView(UUID workspaceId, UUID organizationId, String name, String role) {}

    public record SessionSummary(UUID id, UUID workspaceId, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
            boolean current) {}


    private final AuthenticationManager authentication;
    private final SessionService sessions;
    private final IdentityService identities;
    private final MembershipService memberships;
    private final InstallationBootstrapService bootstrap;
    private final SessionCookies cookies;
    private final LoginThrottle throttle;
    private final java.time.Clock clock;

    public AuthController(AuthenticationManager authentication, SessionService sessions, IdentityService identities,
            MembershipService memberships, InstallationBootstrapService bootstrap, SessionCookies cookies,
            LoginThrottle throttle, java.time.Clock clock) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.cookies = Objects.requireNonNull(cookies, "cookies");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Verifies a password and issues a new session; any session the browser already held is revoked. */
    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> login(HttpServletRequest request, HttpServletResponse response,
            @RequestBody LoginRequest body) {
        if (!throttle.tryAcquire(request.getRemoteAddr())) return throttled(throttle.retryAfterSeconds(
                request.getRemoteAddr()));
        // Separately limits attempts against one account from any number of addresses.
        if (!throttle.tryAcquireAccount(body.username())) {
            return throttled(throttle.accountRetryAfterSeconds(body.username()));
        }
        Authentication verified;
        try {
            verified = authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
                    body.username() == null ? "" : body.username(), body.password() == null ? "" : body.password()));
        } catch (AuthenticationException failed) {
            throw IdentityException.authenticationFailed();
        }
        if (!(verified.getPrincipal() instanceof UUID userId)) throw IdentityException.authenticationFailed();
        EstablishedSession session = sessions.establish(userId, body.workspaceId(),
                cookies.read(request), "LOGIN_SUCCEEDED", "password");
        cookies.write(response, session.cookieValue(), session.expiresAt(), clock.instant());
        return ResponseEntity.ok(SessionView.of(session.principal(), session.csrfToken(), session.expiresAt()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletResponse response) {
        sessions.logout(principal);
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    public SessionView session(@AuthenticationPrincipal AuthenticatedPrincipal principal, HttpServletRequest request) {
        String cookie = AuthenticationMethod.SESSION.equals(principal.method()) ? cookies.read(request) : null;
        return SessionView.of(principal, cookie == null ? null : CredentialCodec.csrfToken(cookie), null);
    }

    /** Changes the password, revokes every session of the user, and issues a fresh one for this browser. */
    @PostMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionView changePassword(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletResponse response, @RequestBody PasswordChange body) {
        identities.changePassword(principal, body.currentPassword(), body.newPassword());
        EstablishedSession session = sessions.establish(principal.subjectId(),
                principal.workspace().map(workspace -> workspace.value()).orElse(null), null, "SESSION_ROTATED",
                "password");
        cookies.write(response, session.cookieValue(), session.expiresAt(), clock.instant());
        return SessionView.of(session.principal(), session.csrfToken(), session.expiresAt());
    }

    @GetMapping("/workspaces")
    public Page<WorkspaceView> workspaces(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // Identity collections are bounded and never paged.
        return Page.of(memberships.workspaces(principal).stream().map(row -> new WorkspaceView(row.workspaceId(),
                row.organizationId(), row.workspaceName(), row.role())).toList());
    }

    /** Selects another of the user's workspaces under a new session value. */
    @PutMapping(path = "/session/workspace", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SessionView selectWorkspace(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            HttpServletRequest request, HttpServletResponse response, @RequestBody WorkspaceSelection body) {
        if (body.workspaceId() == null) {
            throw new IdentityException(IdentityException.INVALID_REQUEST, "A workspaceId is required.");
        }
        EstablishedSession session = sessions.switchWorkspace(principal, cookies.read(request), body.workspaceId());
        cookies.write(response, session.cookieValue(), session.expiresAt(), clock.instant());
        return SessionView.of(session.principal(), session.csrfToken(), session.expiresAt());
    }

    @GetMapping("/sessions")
    public Page<SessionSummary> sessions(@AuthenticationPrincipal AuthenticatedPrincipal principal) {
        // Identity collections are bounded and never paged.
        return Page.of(sessions.list(principal).stream().map(row -> new SessionSummary(row.id(), row.workspaceId(),
                row.createdAt(), row.lastSeenAt(), row.expiresAt(), row.id().equals(principal.credentialId()))).toList());
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revokeSession(@AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id, HttpServletResponse response) {
        sessions.revoke(principal, id);
        if (id.equals(principal.credentialId())) cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    /** Consumes the one-time bootstrap token and creates the first administrator. */
    @PostMapping(path = "/bootstrap", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> bootstrap(HttpServletRequest request, @RequestBody BootstrapRequest body) {
        if (!throttle.tryAcquire(request.getRemoteAddr())) return throttled(throttle.retryAfterSeconds(
                request.getRemoteAddr()));
        Completed completed = bootstrap.complete(body.token(), body.username(), body.displayName(), body.password(),
                body.organizationName(), body.workspaceName());
        return ResponseEntity.status(HttpStatus.CREATED).body(completed);
    }

    private ResponseEntity<ProblemDetail> throttled(long retryAfterSeconds) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                // One text for every limit: it says nothing about which limit applied or about the account.
                "Too many attempts; retry later.");
        problem.setType(URI.create("urn:dvaraos:problem:too-many-attempts"));
        problem.setProperty("code", IdentityException.TOO_MANY_ATTEMPTS);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
