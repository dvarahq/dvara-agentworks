package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;

import java.util.Objects;
import java.util.UUID;

/** Deterministic restart-safe callback tokens derived from an operator-managed HMAC secret. */
public final class OperationCallbackTokens {
    private final IdempotencyDigests digests;

    public OperationCallbackTokens(IdempotencyDigests digests) {
        this.digests = Objects.requireNonNull(digests, "digests");
    }

    public String issue(UUID workspaceId, UUID operationId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(operationId, "operationId");
        return digests.key(workspaceId.toString(), "operation.callback", operationId.toString()).hex();
    }

    public int activeVersion() {
        return digests.activeVersion();
    }

    /** Reissues the same token after restart, even when another HMAC version is now active. */
    public String issue(int version, UUID workspaceId, UUID operationId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(operationId, "operationId");
        return digests.key(version, workspaceId.toString(), "operation.callback", operationId.toString())
                .orElseThrow(() -> new IllegalStateException(
                        "durable operation callback HMAC version " + version + " is not retained"))
                .hex();
    }
}
