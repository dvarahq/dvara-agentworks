package com.dvarahq.agentworks.reference.remote;

import com.dvarahq.agentworks.sdk.AgentWorksClient;
import com.dvarahq.agentworks.sdk.ApiCall;
import com.dvarahq.agentworks.sdk.ApiDocument;
import com.dvarahq.agentworks.sdk.ApiOperation;
import tools.jackson.databind.JsonNode;

import java.net.URI;

/** Minimal standalone SDK client. The token remains application-owned and is resolved for each request. */
public final class RemoteReferenceApplication {
    private RemoteReferenceApplication() { }

    public static ApiDocument getRun(AgentWorksClient client, String runId) {
        return client.execute(ApiCall.operation(ApiOperation.GET_RUN).path("id", runId).build());
    }

    public static ApiDocument submitRun(AgentWorksClient client, String deploymentId,
                                        String idempotencyKey, JsonNode request) {
        return client.execute(ApiCall.operation(ApiOperation.SUBMIT_RUN)
                .path("deploymentId", deploymentId).idempotencyKey(idempotencyKey).body(request).build());
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("usage: RemoteReferenceApplication <base-url> <run-id>");
            return;
        }
        try (AgentWorksClient client = AgentWorksClient.builder(URI.create(args[0])).build()) {
            System.out.println(getRun(client, args[1]).body());
        }
    }
}
