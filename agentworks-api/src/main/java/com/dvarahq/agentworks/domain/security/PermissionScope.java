package com.dvarahq.agentworks.domain.security;

import java.util.Objects;
import java.util.UUID;

/** The tenant boundary at which a remembered permission was granted. */
public record PermissionScope(Level level, UUID id) {
    public enum Level { ORGANIZATION, WORKSPACE }

    public PermissionScope {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
    }

    public static PermissionScope organization(UUID organizationId) {
        return new PermissionScope(Level.ORGANIZATION, organizationId);
    }

    public static PermissionScope workspace(UUID workspaceId) {
        return new PermissionScope(Level.WORKSPACE, workspaceId);
    }
}
