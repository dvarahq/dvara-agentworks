package com.dvarahq.agentworks.domain.identity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Core workspace membership roles: administration, authoring, operation, and read-only access. Authoring and
 * operation are deliberately disjoint so that the people who change an agent are not implicitly the people who
 * promote and run it; an administrator holds both.
 */
public enum WorkspaceRole {
    VIEWER(EnumSet.of(WorkspacePermission.WORKSPACE_READ, WorkspacePermission.INTERACTION_RESPOND,
            WorkspacePermission.TOKEN_SELF)),
    AUTHOR(EnumSet.of(WorkspacePermission.WORKSPACE_READ, WorkspacePermission.TOKEN_SELF,
            WorkspacePermission.AGENT_WRITE, WorkspacePermission.RELEASE_WRITE,
            WorkspacePermission.INTERACTION_RESPOND, WorkspacePermission.ARTIFACT_WRITE)),
    OPERATOR(EnumSet.of(WorkspacePermission.WORKSPACE_READ, WorkspacePermission.TOKEN_SELF,
            WorkspacePermission.DEPLOYMENT_OPERATE, WorkspacePermission.RUN_ADMIT,
            WorkspacePermission.INTERACTION_RESPOND)),
    ADMIN(EnumSet.allOf(WorkspacePermission.class));

    private final Set<WorkspacePermission> permissions;

    WorkspaceRole(Set<WorkspacePermission> permissions) {
        this.permissions = Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    public Set<WorkspacePermission> permissions() {
        return permissions;
    }

    /** True when every permission of {@code other} is also a permission of this role. */
    public boolean covers(WorkspaceRole other) {
        return permissions.containsAll(other.permissions);
    }

    /**
     * The role that describes {@code permissions}: the role with the most permissions that they cover (the first
     * declared on a tie), or empty when they cover no role, not even {@link #VIEWER}: a principal is never labelled
     * with a role whose permissions it lacks (#203). It labels a principal whose effective permissions are an
     * intersection, such as a token's role narrowed by the current membership; authorization always uses the
     * permissions themselves. Deriving the label from coverage rather than an exact match means a future change to a
     * role's permissions can never make a valid credential fail to authenticate (#177).
     */
    public static Optional<WorkspaceRole> effective(Set<WorkspacePermission> permissions) {
        WorkspaceRole best = null;
        for (WorkspaceRole role : values()) {
            if (permissions.containsAll(role.permissions)
                    && (best == null || role.permissions.size() > best.permissions.size())) {
                best = role;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Parses a stored or requested role name; unknown names are absent rather than defaulted. */
    public static Optional<WorkspaceRole> parse(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(valueOf(value.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
