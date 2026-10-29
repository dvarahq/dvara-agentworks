package com.dvarahq.agentworks.domain.identity;

import com.dvarahq.agentworks.domain.tenant.OrganizationId;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated actor of one request, propagated to REST handlers, audit records, and control actions, and
 * the value later Console, CLI, SSE, invocation-context, and channel boundaries consume. It carries identifiers
 * only, never a password, session value, or token; {@code credentialId} names the session or API token record
 * that authenticated the request so that it can be audited and revoked.
 *
 * @param workspaceId the single workspace this request may act in, or {@code null} when the principal has none
 * @param role the effective role in {@code workspaceId}: the membership role, or for an API token the role its
 *             narrowed permissions amount to; {@code null} without a workspace, or when those permissions amount to
 *             no role (#203), which is never labelled as a role it does not cover
 * @param permissions the effective permissions: for an API token, the intersection of its role and the owner's
 *                    current membership role
 * @param organizationAdmin whether this request may administer the organization's users (session only)
 * @param mustChangePassword while set, the principal may only change its password, inspect, or end its session
 */
public record AuthenticatedPrincipal(UUID subjectId, OrganizationId organizationId, String username,
        WorkspaceId workspaceId, WorkspaceRole role, Set<WorkspacePermission> permissions,
        boolean organizationAdmin, AuthenticationMethod method, UUID credentialId, boolean mustChangePassword) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(credentialId, "credentialId");
        if (workspaceId == null && role != null) {
            throw new IllegalArgumentException("a workspace role requires a workspace");
        }
        // Normalized first, so every invariant below reads the same set: null is no permission (#207).
        permissions = permissions == null || permissions.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(permissions));
        // Only an API token's narrowed permissions can amount to no role (#203), and its role is always the label they
        // amount to, so a dropped or wrong label fails closed; any other principal with a workspace holds its
        // membership role.
        if (AuthenticationMethod.API_TOKEN.equals(method)) {
            if (role != WorkspaceRole.effective(permissions).orElse(null)) {
                throw new IllegalArgumentException("an API token's role is the role its permissions amount to");
            }
        } else if (workspaceId != null && role == null) {
            throw new IllegalArgumentException("a workspace and its membership role are bound together");
        }
        if (workspaceId == null && !permissions.isEmpty()) {
            throw new IllegalArgumentException("workspace permissions require a workspace");
        }
    }

    public Optional<WorkspaceId> workspace() {
        return Optional.ofNullable(workspaceId);
    }

    /** True only when the permission is effective and no password change is pending. */
    public boolean can(WorkspacePermission permission) {
        return !mustChangePassword && permissions.contains(permission);
    }

    /**
     * The stable audit actor recorded on control actions: the subject and how it authenticated. Session rotation
     * does not change it, so an idempotent retry after signing in again still matches; an API token is named
     * because it is an independently revocable credential.
     */
    public String actor() {
        if (AuthenticationMethod.API_TOKEN.equals(method)) return "user:" + subjectId + "/api-token:" + credentialId;
        return "user:" + subjectId + "/" + method.value();
    }
}
