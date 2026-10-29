package com.dvarahq.agentworks.runplane.compiled;

import java.util.Objects;
import java.util.UUID;

/** Exact tenant and immutable release identity of reusable executable material. */
public record CompiledReleaseKey(UUID workspaceId, UUID releaseId, int manifestVersion, String dependencyHash) {
    public CompiledReleaseKey {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(releaseId, "releaseId");
        if (manifestVersion < 1) throw new IllegalArgumentException("manifestVersion must be positive");
        dependencyHash = digest(dependencyHash, "dependencyHash");
    }

    static String digest(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("sha256:[0-9a-f]{64}")) throw new IllegalArgumentException(name + " must be a digest");
        return value;
    }
}
