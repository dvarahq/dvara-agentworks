package com.dvarahq.agentworks.reference.remote;

import com.dvarahq.agentworks.sdk.AgentWorksClient;
import com.dvarahq.agentworks.sdk.ApiDocument;
import com.dvarahq.agentworks.sdk.ApiOperation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteReferenceApplicationTest {
    @Test void standaloneJourneyUsesOnlyThePublicSdkBoundary() {
        AtomicReference<ApiOperation> operation = new AtomicReference<>();
        AgentWorksClient client = new AgentWorksClient(call -> {
            operation.set(call.operation());
            return new ApiDocument(200, JsonMapper.builder().build().createObjectNode().put("id", "run-1"), Map.of());
        }, (run, options, after) -> { throw new AssertionError("not streaming in this probe"); });

        assertThat(RemoteReferenceApplication.getRun(client, "run-1").body().get("id").asString())
                .isEqualTo("run-1");
        assertThat(operation).hasValue(ApiOperation.GET_RUN);
    }

    @Test void promotionKeepsOldCompatibleWorkPinnedAndRoutesNewSessionRunToNewRevision() {
        var scenario = MultiAgentLifecycleExample.demonstrate();
        assertThat(scenario.oldCompatibleChild().deploymentRevisionId())
                .isEqualTo(scenario.oldRunResume().deploymentRevisionId());
        assertThat(scenario.newSessionRun().deploymentRevisionId())
                .isNotEqualTo(scenario.oldRunResume().deploymentRevisionId());
        assertThat(scenario.retirementBlocker().quiescent()).isFalse();
        assertThat(scenario.oldCompatibleChild().credentialPolicy().credentialRef())
                .startsWith("credential://");
    }
}
