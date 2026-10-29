package com.dvarahq.agentworks.controlplane.security.web;

import com.dvarahq.agentworks.controlplane.security.RememberedPermissionService;
import com.dvarahq.agentworks.domain.security.PermissionRisk;
import com.dvarahq.agentworks.domain.security.PermissionScope;
import com.dvarahq.agentworks.domain.security.RememberedPermissionRule;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class RememberedPermissionApiResources {
    private RememberedPermissionApiResources() {}

    public record CreateRequest(UUID id, RememberedPermissionRule.Effect effect, PermissionScope.Level scope, UUID actorId,
            UUID agentDefinitionId, UUID releaseId, String capability, String resource, String action,
            UUID environmentId, PermissionRisk maximumRisk, Instant validFrom, Instant expiresAt) {
        RememberedPermissionService.Draft value() {
            return new RememberedPermissionService.Draft(required(id, "id"), required(effect, "effect"),
                    required(scope, "scope"), required(actorId, "actorId"),
                    Optional.ofNullable(agentDefinitionId), Optional.ofNullable(releaseId),
                    required(capability, "capability"), required(resource, "resource"), required(action, "action"),
                    required(environmentId, "environmentId"), required(maximumRisk, "maximumRisk"),
                    Optional.ofNullable(validFrom), required(expiresAt, "expiresAt"));
        }

        private static <T> T required(T value, String field) {
            if (value == null) throw new IllegalArgumentException(field + " is required");
            return value;
        }
    }

    public record Resource(UUID id, long version, RememberedPermissionRule.Effect effect,
            PermissionScope.Level scope, UUID scopeId, UUID actorId, UUID agentDefinitionId, UUID releaseId,
            String capability, String resource, String action, UUID environmentId, PermissionRisk maximumRisk,
            Instant validFrom, Instant expiresAt, Instant revokedAt) {
        static Resource of(RememberedPermissionRule rule) {
            return new Resource(rule.id(), rule.version(), rule.effect(), rule.scope().level(), rule.scope().id(),
                    rule.actorId(), rule.agentDefinitionId().orElse(null), rule.releaseId().orElse(null),
                    rule.capability(), rule.resource(), rule.action(), rule.environmentId(), rule.maximumRisk(),
                    rule.validFrom(), rule.expiresAt(), rule.revokedAt().orElse(null));
        }
    }
}
