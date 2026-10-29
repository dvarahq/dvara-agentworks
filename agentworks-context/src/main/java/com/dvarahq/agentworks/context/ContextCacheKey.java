package com.dvarahq.agentworks.context;

import java.util.Objects;
import java.util.UUID;

/** Exact identity of reusable AOT material; no mutable agent/deployment identity is sufficient. */
public record ContextCacheKey(UUID workspaceId, UUID releaseId, UUID deploymentRevisionId,
        String compilerPolicyHash, String dependencyHash) {
    public ContextCacheKey {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(releaseId, "releaseId");
        Objects.requireNonNull(deploymentRevisionId, "deploymentRevisionId");
        compilerPolicyHash = digest(compilerPolicyHash, "compilerPolicyHash");
        dependencyHash = digest(dependencyHash, "dependencyHash");
    }
    private static String digest(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be a digest");
        return value;
    }
}
