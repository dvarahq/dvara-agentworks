package com.dvarahq.agentworks.interaction;

import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Exact responder boundary inside one workspace. Application permission checks remain an additional hard gate. */
public record HumanInteractionAssignment(UUID workspaceId, Level level, Optional<UUID> principalId) {
    public enum Level { WORKSPACE, USER, SESSION, DELEGATED_SUBJECT }

    public HumanInteractionAssignment {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(level, "level");
        principalId = Objects.requireNonNull(principalId, "principalId");
        if ((level == Level.WORKSPACE) != principalId.isEmpty()) {
            throw new IllegalArgumentException("only a WORKSPACE assignment has no principal id");
        }
    }

    public static HumanInteractionAssignment workspace(UUID workspaceId) {
        return new HumanInteractionAssignment(workspaceId, Level.WORKSPACE, Optional.empty());
    }

    public static HumanInteractionAssignment user(UUID workspaceId, UUID userId) {
        return identified(workspaceId, Level.USER, userId);
    }

    public static HumanInteractionAssignment session(UUID workspaceId, UUID sessionId) {
        return identified(workspaceId, Level.SESSION, sessionId);
    }

    public static HumanInteractionAssignment delegatedSubject(UUID workspaceId, UUID subjectId) {
        return identified(workspaceId, Level.DELEGATED_SUBJECT, subjectId);
    }

    public boolean permits(InvocationIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!workspaceId.equals(identity.workspaceId())) return false;
        return switch (level) {
            case WORKSPACE -> true;
            case USER -> principalId.get().equals(identity.userId());
            case SESSION -> principalId.get().equals(identity.sessionId());
            case DELEGATED_SUBJECT -> principalId.get().equals(identity.delegatedSubjectId());
        };
    }

    private static HumanInteractionAssignment identified(UUID workspaceId, Level level, UUID id) {
        return new HumanInteractionAssignment(workspaceId, level,
                Optional.of(Objects.requireNonNull(id, "principalId")));
    }
}
