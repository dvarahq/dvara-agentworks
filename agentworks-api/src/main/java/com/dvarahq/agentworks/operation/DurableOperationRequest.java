package com.dvarahq.agentworks.operation;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Provider-neutral request. operationId is the provider idempotency key and callback correlation identity. */
public record DurableOperationRequest(UUID operationId, UUID workspaceId, UUID sessionId, UUID runId,
        String operationType, Map<String, Object> input, String callbackUrl, String callbackToken, Instant deadline) {
    public DurableOperationRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(deadline, "deadline");
        input = Map.copyOf(Objects.requireNonNull(input, "input"));
        if (operationType == null || operationType.isBlank() || operationType.length() > 128) {
            throw new IllegalArgumentException("operationType must be 1 to 128 characters");
        }
        if (callbackUrl == null || callbackUrl.isBlank() || callbackUrl.length() > 2048) {
            throw new IllegalArgumentException("callbackUrl must be 1 to 2048 characters");
        }
        if (callbackToken == null || callbackToken.length() < 32 || callbackToken.length() > 512) {
            throw new IllegalArgumentException("callbackToken must be 32 to 512 characters");
        }
    }

    @Override public String toString() {
        return "DurableOperationRequest[operationId=" + operationId + ", workspaceId=" + workspaceId
                + ", sessionId=" + sessionId + ", runId=" + runId + ", operationType=" + operationType
                + ", input=<redacted>, callbackToken=<redacted>, deadline=" + deadline + "]";
    }
}
