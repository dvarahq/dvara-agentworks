package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Without authentication, every agent and lifecycle operation is refused and nothing is written. */
@SpringBootTest(classes = DvaraAgentWorksApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "agentworks.tenancy.bootstrap-default=false")
class AgentDefinitionApiFailClosedTest {
    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everyOperationIsForbiddenAndNothingIsWritten() {
        var api = new AgentApiClient(port);
        String definition = UUID.randomUUID().toString();
        // An untrusted identity header is never an authentication.
        UUID claimed = UUID.randomUUID();

        var responses = List.of(
                api.post("/api/v1/agents", "application/json", "{\"name\":\"agent\"}", claimed),
                api.get("/api/v1/agents", claimed),
                api.get("/api/v1/agents/" + definition, claimed),
                api.post("/api/v1/agents/" + definition + "/versions", "application/yaml", "kind: Agent", claimed),
                api.get("/api/v1/agents/" + definition + "/versions", claimed),
                api.get("/api/v1/agents/" + definition + "/versions/1.0.0", claimed),
                api.post("/api/v1/environments", "application/json", "{\"name\":\"prod\"}", claimed),
                api.get("/api/v1/deployments", claimed),
                api.post("/api/v1/agent-versions/" + definition + "/releases", "application/json", "{\"name\":\"r\"}", claimed),
                api.post("/api/v1/deployments/" + definition + "/runs", "application/json", "{}", claimed));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("AUTHENTICATION_REQUIRED");
        });
        assertThat(jdbc.queryForObject("select count(*) from agent_definition", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from agent_environment", Integer.class)).isZero();
    }
}
