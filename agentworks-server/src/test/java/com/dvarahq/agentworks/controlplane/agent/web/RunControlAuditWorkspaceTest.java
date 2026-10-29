package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #196 review, low 4: a run control's audit record names the workspace the request was resolved in, the run's, and
 * that workspace's organization, not the principal's own, so a deployment whose {@link WorkspaceScopeResolver} resolves
 * a workspace other than the principal's records the run's workspace, as the shipped resolver does.
 */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestWorkspaces.class, RunPauseApiTest.CheckpointingExecutor.class,
        RunControlAuditWorkspaceTest.HeaderResolver.class})
class RunControlAuditWorkspaceTest {
    static final String HEADER = "X-Test-Resolved-Workspace";

    /** Resolves the workspace named by a test header, and otherwise the principal's, as the shipped resolver does. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HeaderResolver {
        @Bean
        WorkspaceScopeResolver headerWorkspaceScopeResolver() {
            WorkspaceScopeResolver principals = new AuthenticatedWorkspaceScopeResolver();
            return request -> request.getHeader(HEADER) == null ? principals.resolve(request)
                    : new WorkspaceId(UUID.fromString(request.getHeader(HEADER)));
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DurableRunEngine engine;

    @Test
    void aPauseRequestAndItsWithdrawalAreAuditedInTheResolvedWorkspace() {
        var api = new AgentApiClient(port);
        UUID workspace = TestWorkspaces.createWorkspace(jdbc);
        UUID principals = TestWorkspaces.createWorkspace(jdbc);
        String environment = api.post("/api/v1/environments", "application/json", "{\"name\":\"prod\"}", workspace)
                .body().path("id").asString();
        String definition = api.post("/api/v1/agents", "application/json", "{\"name\":\"agent\"}", workspace).body()
                .path("id").asString();
        String version = api.post("/api/v1/agents/" + definition + "/versions", "application/yaml",
                "apiVersion: dvarahq.com/v1alpha1\nkind: Agent\nmetadata: {name: agent, version: 1.0.0}\n"
                        + "spec:\n  executor: {type: checkpointing-test}\n", workspace).body().path("id").asString();
        String release = api.post("/api/v1/agent-versions/" + version + "/releases", "application/json",
                "{\"name\":\"agent-1\"}", workspace).body().path("id").asString();
        String deployment = api.send("POST", "/api/v1/environments/" + environment + "/promotions",
                "{\"deployment\":\"agent\",\"releaseId\":\"" + release + "\"}", workspace, Map.of()).body()
                .path("deploymentId").asString();
        UUID run = UUID.fromString(api.send("POST", "/api/v1/deployments/" + deployment + "/runs",
                "{\"input\":{\"q\":1}}", workspace, Map.of()).body().path("id").asString());
        engine.claimNext("audit-test-worker", ClaimScope.any(ReleaseManifestReader.SUPPORTED_VERSIONS)
                .in(new WorkspaceId(workspace)), Duration.ofMinutes(1)).orElseThrow();

        // An administrator of another workspace, whose requests resolve to the run's workspace.
        Map<String, String> resolved = Map.of(HEADER, workspace.toString());
        assertThat(api.send("POST", "/api/v1/runs/" + run + "/pause", "{}", principals, resolved).status())
                .isEqualTo(202);
        assertThat(api.send("POST", "/api/v1/runs/" + run + "/resume", "{}", principals, resolved).status())
                .isEqualTo(200);

        UUID organization = jdbc.queryForObject("select organization_id from workspace where id = ?", UUID.class,
                workspace);
        assertThat(jdbc.queryForList("select event_type || ':' || workspace_id || ':' || organization_id from "
                + "auth_audit_event where target_id = ? order by occurred_at", String.class, run))
                .containsExactly("RUN_PAUSE_REQUESTED:" + workspace + ":" + organization,
                        "RUN_PAUSE_WITHDRAWN:" + workspace + ":" + organization);
    }
}
