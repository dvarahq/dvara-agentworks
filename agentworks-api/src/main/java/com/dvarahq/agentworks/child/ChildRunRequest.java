package com.dvarahq.agentworks.child;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, least-authority request recorded before a child is allocated. */
public record ChildRunRequest(UUID targetDeploymentId, UUID expectedReleaseId, String task,
        Map<String, Object> context, Set<String> tools, ChildRunBudget budget,
        ChildFailurePolicy failurePolicy, String idempotencyKey) {
    public ChildRunRequest {
        Objects.requireNonNull(targetDeploymentId, "targetDeploymentId");
        Objects.requireNonNull(expectedReleaseId, "expectedReleaseId");
        if (task == null || task.isBlank() || task.length() > 16_384) {
            throw new IllegalArgumentException("child task is 1 to 16384 characters");
        }
        context = Map.copyOf(Objects.requireNonNull(context, "context"));
        tools = Set.copyOf(Objects.requireNonNull(tools, "tools"));
        if (tools.size() > 128 || tools.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 128)) {
            throw new IllegalArgumentException("child tools are bounded nonblank identifiers");
        }
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("child idempotency key is 1 to 128 characters");
        }
    }
}
