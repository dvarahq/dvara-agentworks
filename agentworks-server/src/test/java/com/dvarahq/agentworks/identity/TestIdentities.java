package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only provisioning of real principals: a member with a given role in a workspace and an API token issued
 * through the production services. REST contract tests authenticate with these tokens exactly as the CLI would.
 * Instances register by server port so that a test's HTTP client finds the principals of its own context.
 */
public final class TestIdentities implements ApplicationListener<WebServerInitializedEvent> {
    private static final Map<Integer, TestIdentities> BY_PORT = new ConcurrentHashMap<>();

    public record Member(UUID userId, String username, UUID workspaceId, WorkspaceRole role, String token) {
        @Override
        public String toString() {
            return "Member[userId=" + userId + ", role=" + role + "]";
        }
    }

    private final JdbcIdentityRepository repository;
    private final SessionService sessions;
    private final ApiTokenService tokens;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final Map<String, Member> members = new ConcurrentHashMap<>();

    public TestIdentities(JdbcIdentityRepository repository, SessionService sessions, ApiTokenService tokens,
            PasswordEncoder encoder, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static TestIdentities forPort(int port) {
        return BY_PORT.get(port);
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        BY_PORT.put(event.getWebServer().getPort(), this);
    }

    /** The cached administrator token of a workspace; {@code null} selects the default workspace. */
    public String token(UUID workspace) {
        return member(workspace == null ? DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value() : workspace,
                WorkspaceRole.ADMIN).token();
    }

    /** A cached member with {@code role} in {@code workspace}, created on first use. */
    public Member member(UUID workspace, WorkspaceRole role) {
        return members.computeIfAbsent(workspace + "/" + role, key -> create(workspace, role));
    }

    /** A new member with {@code role} in {@code workspace} and its own token; it cannot sign in with a password. */
    public Member create(UUID workspace, WorkspaceRole role) {
        return create(workspace, role, null, "MEMBER");
    }

    /**
     * A new member with {@code role} in {@code workspace}, its own token, and, when {@code password} is given, a
     * password for browser sign-in.
     */
    public Member create(UUID workspace, WorkspaceRole role, String password, String organizationRole) {
        UUID organization = repository.workspaceOrganization(workspace)
                .orElseThrow(() -> new IllegalStateException("unknown workspace " + workspace));
        UUID user = UUID.randomUUID();
        String username = "member-" + user;
        var now = clock.instant();
        repository.insertUser(user, organization, username, "Test member", organizationRole,
                password == null ? "{argon2}unusable" : encoder.encode(password), false, now);
        repository.insertMembership(workspace, organization, user, role.name(), now);
        var session = sessions.establish(user, workspace, null, "LOGIN_SUCCEEDED", "test");
        var issued = tokens.issue(session.principal(), "test-" + role.name().toLowerCase(java.util.Locale.ROOT),
                null, null);
        // Only the token remains; the provisioning session never counts toward session limits.
        sessions.logout(session.principal());
        return new Member(user, username, workspace, role, issued.token());
    }
}
