package com.dvarahq.agentworks.domain.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One immutable version of a remembered permission. A later version with the same {@code id} supersedes it. Agent
 * and release selectors are optional only to make an intentionally broader grant explicit; every other dimension is
 * exact. Resolved credentials and user-provided tool arguments never belong in this contract.
 */
public record RememberedPermissionRule(
        UUID id,
        long version,
        Effect effect,
        PermissionScope scope,
        UUID actorId,
        Optional<UUID> agentDefinitionId,
        Optional<UUID> releaseId,
        String capability,
        String resource,
        String action,
        UUID environmentId,
        PermissionRisk maximumRisk,
        Instant validFrom,
        Instant expiresAt,
        Optional<Instant> revokedAt) {

    public enum Effect { ALLOW, DENY }

    public RememberedPermissionRule {
        Objects.requireNonNull(id, "id");
        if (version < 1) throw new IllegalArgumentException("permission version must be positive");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(actorId, "actorId");
        agentDefinitionId = Objects.requireNonNull(agentDefinitionId, "agentDefinitionId");
        releaseId = Objects.requireNonNull(releaseId, "releaseId");
        capability = exact(capability, "capability", 128);
        resource = exact(resource, "resource", 512);
        action = exact(action, "action", 64);
        Objects.requireNonNull(environmentId, "environmentId");
        Objects.requireNonNull(maximumRisk, "maximumRisk");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(expiresAt, "expiresAt");
        revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
        if (!validFrom.isBefore(expiresAt)) {
            throw new IllegalArgumentException("permission expiry must be after validFrom");
        }
        if (revokedAt.isPresent() && revokedAt.get().isBefore(validFrom)) {
            throw new IllegalArgumentException("permission cannot be revoked before validFrom");
        }
    }

    private static String exact(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be a non-blank exact value");
        }
        return value;
    }
}
