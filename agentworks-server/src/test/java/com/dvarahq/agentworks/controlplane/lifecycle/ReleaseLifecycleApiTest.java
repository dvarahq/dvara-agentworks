package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.domain.identity.WorkspaceRole;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.TestIdentities;
import com.dvarahq.agentworks.identity.web.IdentityHttp;
import com.dvarahq.agentworks.identity.web.IdentityTestConfiguration;
import com.dvarahq.agentworks.runplane.exposure.ExposureException;
import com.dvarahq.agentworks.runplane.exposure.ExposureRevisionSource;
import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end release lifecycle, cohort propagation, admission fence, revocation history, and audit (#79). */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityTestConfiguration.class)
class ReleaseLifecycleApiTest {
    private static final UUID WORKSPACE = DefaultTenantBootstrap.DEFAULT_WORKSPACE_ID.value();

    @LocalServerPort int port;
    @Autowired TestIdentities identities;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExposureRevisionSource exposures;

    @Test
    void suspensionResumesAndSigningKeyRevocationIsTerminalAndReplaySafe() {
        var admin = identities.create(WORKSPACE, WorkspaceRole.ADMIN);
        var client = new IdentityHttp(port).bearer(admin.token());
        String release = release(client);
        String environment = client.send("POST", "/api/v1/environments",
                Map.of("name", "security-" + UUID.randomUUID())).json().path("id").asString();
        var promoted = client.send("POST", "/api/v1/environments/" + environment + "/promotions",
                Map.of("deployment", "api", "releaseId", release));
        UUID deployment = UUID.fromString(promoted.json().path("deploymentId").asString());
        String cohort = promoted.json().path("cohortId").asString();

        var initial = client.get("/api/v1/releases/" + release + "/lifecycle");
        var suspended = action(client, release, "suspend", 1, "suspend-" + UUID.randomUUID(),
                Map.of("reason", "security investigation"));
        String suspendedCohortState = client.get("/api/v1/cohorts/" + cohort).json().path("state").asString();
        assertThatThrownBy(() -> exposures.read(new com.dvarahq.agentworks.domain.tenant.WorkspaceId(WORKSPACE),
                deployment, null)).isInstanceOfSatisfying(ExposureException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXPOSURE_UNAVAILABLE"));
        var resumed = action(client, release, "resume", 2, "resume-" + UUID.randomUUID(),
                Map.of("reason", "investigation cleared"));
        String resumedCohortState = client.get("/api/v1/cohorts/" + cohort).json().path("state").asString();
        assertThat(exposures.read(new com.dvarahq.agentworks.domain.tenant.WorkspaceId(WORKSPACE), deployment, null))
                .isNotNull();
        String revokeKey = "revoke-" + UUID.randomUUID();
        Map<String, Object> revoke = Map.of("reason", "signing key compromised", "subjectKind", "SIGNING_KEY",
                "subjectIdentity", "key:test", "evidenceDigest", "sha256:" + "a".repeat(64));
        var revoked = action(client, release, "revoke", 3, revokeKey, revoke);
        assertThatThrownBy(() -> exposures.read(new com.dvarahq.agentworks.domain.tenant.WorkspaceId(WORKSPACE),
                deployment, null)).isInstanceOfSatisfying(ExposureException.class,
                        failure -> assertThat(failure.code()).isEqualTo("EXPOSURE_UNAVAILABLE"));
        var replay = action(client, release, "revoke", 3, revokeKey, revoke);
        var blocked = client.send("POST", "/api/v1/environments/" + environment + "/promotions",
                Map.of("deployment", "replacement", "releaseId", release));
        var history = client.get("/api/v1/release-revocations?releaseId=" + release + "&subjectKind=SIGNING_KEY");

        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.json().path("state").asString()).isEqualTo("AVAILABLE");
        assertThat(suspended.status()).as(suspended.raw()).isEqualTo(200);
        assertThat(suspended.json().at("/lifecycle/state").asString()).isEqualTo("SUSPENDED");
        assertThat(suspendedCohortState).isEqualTo("SUSPENDED");
        assertThat(resumed.status()).as(resumed.raw()).isEqualTo(200);
        assertThat(resumedCohortState).isEqualTo("ROUTABLE");
        assertThat(client.get("/api/v1/cohorts/" + cohort).json().path("state").asString()).isEqualTo("REVOKED");
        assertThat(resumed.json().at("/lifecycle/state").asString()).isEqualTo("AVAILABLE");
        assertThat(revoked.status()).as(revoked.raw()).isEqualTo(200);
        assertThat(revoked.json().at("/revocation/subjectKind").asString()).isEqualTo("SIGNING_KEY");
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.json().path("decisionId").asString()).isEqualTo(revoked.json().path("decisionId").asString());
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.code()).isEqualTo("RELEASE_REVOKED");
        assertThat(history.json().path("items")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from auth_audit_event where target_id = ? "
                + "and event_type = 'RELEASE_REVOKED'", Integer.class, UUID.fromString(release))).isOne();
    }

    private String release(IdentityHttp client) {
        String name = "release-security-" + UUID.randomUUID();
        String definition = client.send("POST", "/api/v1/agents", Map.of("name", name)).json().path("id").asString();
        String version = client.send("POST", "/api/v1/agents/" + definition + "/versions",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: " + name
                        + ", version: 1.0.0}\nspec:\n  executor: {type: spring-ai}\n",
                Map.of("Content-Type", "application/yaml")).json().path("id").asString();
        return client.send("POST", "/api/v1/agent-versions/" + version + "/releases",
                Map.of("name", "r-" + UUID.randomUUID())).json().path("id").asString();
    }

    private IdentityHttp.Response action(IdentityHttp client, String release, String action, long version, String key,
            Map<String, Object> body) {
        return client.send("POST", "/api/v1/releases/" + release + "/" + action, body,
                Map.of("If-Match", "\"" + version + "\"", "Idempotency-Key", key));
    }
}
