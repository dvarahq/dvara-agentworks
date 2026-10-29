package com.dvarahq.agentworks.sdk;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiContractTest {
    @Test void operationBuildsBoundedEncodedPathAndPreservesConcurrencyHeaders() {
        ApiCall call = ApiCall.operation(ApiOperation.TRANSITION_COHORT)
                .path("id", "cohort / one").path("action", "suspend")
                .query("reason", "planned drain")
                .idempotencyKey("operation-1").ifMatch("\"v3\"")
                .body(JsonNodeFactory.instance.objectNode().put("reason", "planned")).build();

        assertThat(call.pathAndQuery()).isEqualTo("/api/v1/cohorts/cohort%20%2F%20one/suspend?reason=planned%20drain");
        assertThat(call.idempotencyKey()).isEqualTo("operation-1");
        assertThat(call.ifMatch()).isEqualTo("\"v3\"");
    }

    @Test void missingPathAndHeaderInjectionFailBeforeTransport() {
        assertThatThrownBy(() -> ApiCall.operation(ApiOperation.GET_RUN).build().pathAndQuery())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing path parameter");
        assertThatThrownBy(() -> ApiCall.operation(ApiOperation.GET_RUN).path("id", "r")
                .idempotencyKey("ok\r\nInjected: yes").build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void clientExposesAgentSpecAndAllMinimumApiFamiliesThroughStableOperations() {
        AgentWorksTransport fake = call -> new ApiDocument(200,
                JsonMapper.builder().build().createObjectNode().put("operation", call.operation().name()), Map.of());
        AgentWorksClient client = new AgentWorksClient(fake, (run, options, after) -> { throw new AssertionError(); });

        for (ApiOperation operation : List.of(ApiOperation.PUBLISH_AGENT_VERSION, ApiOperation.CREATE_RELEASE,
                ApiOperation.PROMOTE, ApiOperation.SUBMIT_RUN, ApiOperation.PLAN_REPLAY,
                ApiOperation.RESPOND_TO_INTERACTION, ApiOperation.GET_OPERATION,
                ApiOperation.UPLOAD_ARTIFACT, ApiOperation.START_EVALUATION)) {
            ApiCall.Builder call = ApiCall.operation(operation);
            for (String name : List.of("definitionId", "versionId", "environmentId", "deploymentId", "id",
                    "interactionId", "operationId", "artifactId")) call.path(name, "id-1");
            assertThat(client.execute(call.build()).body().get("operation").asString()).isEqualTo(operation.name());
        }
        assertThat(client.specs()).isNotNull();
    }

    @Test void problemMessageContainsOnlyStatusAndStableCode() {
        var problem = JsonNodeFactory.instance.objectNode().put("code", "FORBIDDEN").put("detail", "secret-canary");
        ApiProblemException exception = new ApiProblemException(403, problem);
        assertThat(exception.getMessage()).isEqualTo("AgentWorks API refused the request: 403 FORBIDDEN")
                .doesNotContain("secret-canary");
    }
}
