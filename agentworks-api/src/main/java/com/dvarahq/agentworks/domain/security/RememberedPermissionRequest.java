package com.dvarahq.agentworks.domain.security;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Complete effect identity evaluated again immediately before use. */
public record RememberedPermissionRequest(
        UUID organizationId,
        UUID workspaceId,
        UUID actorId,
        UUID agentDefinitionId,
        UUID releaseId,
        String capability,
        String resource,
        String action,
        UUID environmentId,
        PermissionRisk risk,
        Instant evaluatedAt) {

    public RememberedPermissionRequest {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(agentDefinitionId, "agentDefinitionId");
        Objects.requireNonNull(releaseId, "releaseId");
        capability = exact(capability, "capability");
        resource = exact(resource, "resource");
        action = exact(action, "action");
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
    }

    private static String exact(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be a non-blank exact value");
        }
        return value;
    }
}
