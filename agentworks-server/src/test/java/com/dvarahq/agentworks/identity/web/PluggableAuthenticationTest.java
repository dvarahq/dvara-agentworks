package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.domain.identity.AuthenticationMethod;
import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.IdentityService;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.TestIdentities.Member;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentication is replaceable without changing tenant or resource authority: an additional provider (here a
 * stand-in for a later external identity provider that has already verified an assertion) maps its subject to a
 * local user, and authorization still comes only from that user's membership (issue #74, acceptance 8).
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({IdentityTestConfiguration.class, PluggableAuthenticationTest.ExternalProvider.class})
class PluggableAuthenticationTest {
    private static final UUID WORKSPACE = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();

    @TestConfiguration(proxyBeanMethods = false)
    static class ExternalProvider {
        /** Accepts "ext.&lt;username&gt;" as an already-verified external assertion. */
        @Bean
        AuthenticationProvider externalAssertionProvider(JdbcIdentityRepository users, IdentityService identities) {
            AuthenticationMethod method = new AuthenticationMethod("external-test");
            return new AuthenticationProvider() {
                @Override
                public Authentication authenticate(Authentication authentication) {
                    String value = ((CredentialAuthenticationToken) authentication).value();
                    if (!value.startsWith("ext.")) throw new BadCredentialsException("not an external assertion");
                    return users.findUserByUsername(value.substring(4))
                            .flatMap(user -> identities.principalFor(user.id(), users.membershipsOf(user.id()).stream()
                                    .findFirst().map(row -> row.workspaceId()).orElse(null), method, UUID.randomUUID()))
                            .map(AgentWorksAuthentication::new)
                            .orElseThrow(() -> new BadCredentialsException("unknown external subject"));
                }

                @Override
                public boolean supports(Class<?> authentication) {
                    return CredentialAuthenticationToken.class.isAssignableFrom(authentication);
                }
            };
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    TestIdentities identities;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anAdditionalProviderAuthenticatesButMembershipStillAuthorizes() {
        Member author = identities.create(WORKSPACE, WorkspaceRole.AUTHOR);
        var external = new IdentityHttp(port).bearer("ext." + author.username());

        var whoami = external.get("/api/v1/auth/session");
        var authored = external.send("POST", "/api/v1/agents", Map.of("name", "external-" + UUID.randomUUID()));
        var operated = external.send("POST", "/api/v1/environments", Map.of("name", "external-env"));
        var minted = external.send("POST", "/api/v1/tokens", Map.of("name", "no"));
        var unknown = new IdentityHttp(port).bearer("ext.nobody").get("/api/v1/agents");

        assertThat(whoami.json().path("authenticationMethod").asString()).isEqualTo("external-test");
        assertThat(whoami.json().path("role").asString()).isEqualTo("AUTHOR");
        assertThat(authored.status()).isEqualTo(201);
        assertThat(operated.code()).isEqualTo("ACCESS_DENIED");
        assertThat(minted.code()).as("only interactive local sessions mint tokens").isEqualTo("ACCESS_DENIED");
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(new IdentityHttp(port).bearer(author.token()).get("/api/v1/agents").status())
                .as("built-in providers keep working").isEqualTo(200);
    }

    @Test
    void membershipRemovalEndsExternalAccessToo() {
        Member viewer = identities.create(WORKSPACE, WorkspaceRole.VIEWER);
        var external = new IdentityHttp(port).bearer("ext." + viewer.username());
        assertThat(external.get("/api/v1/agents").status()).isEqualTo(200);

        jdbc.update("delete from workspace_membership where subject_id = ?", viewer.userId());
        var afterRemoval = external.get("/api/v1/agents");

        assertThat(afterRemoval.status()).isEqualTo(403);
        assertThat(afterRemoval.code()).isEqualTo("WORKSPACE_SCOPE_UNAVAILABLE");
    }
}
