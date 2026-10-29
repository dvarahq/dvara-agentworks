package com.dvarahq.agentworks.credential;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** One exact authority boundary for a configured credential reference. */
public record CredentialScope(Level level, Optional<UUID> id) {
    public enum Level { PLATFORM, ORGANIZATION, WORKSPACE, AGENT, USER, SESSION }

    public CredentialScope {
        Objects.requireNonNull(level, "level");
        id = Objects.requireNonNull(id, "id");
        if ((level == Level.PLATFORM) != id.isEmpty()) {
            throw new IllegalArgumentException("only PLATFORM scope has no identity");
        }
    }

    public static CredentialScope platform() { return new CredentialScope(Level.PLATFORM, Optional.empty()); }
    public static CredentialScope organization(UUID id) { return identified(Level.ORGANIZATION, id); }
    public static CredentialScope workspace(UUID id) { return identified(Level.WORKSPACE, id); }
    public static CredentialScope agent(UUID id) { return identified(Level.AGENT, id); }
    public static CredentialScope user(UUID id) { return identified(Level.USER, id); }
    public static CredentialScope session(UUID id) { return identified(Level.SESSION, id); }

    public boolean permits(CredentialUseRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (level) {
            case PLATFORM -> true;
            case ORGANIZATION -> id.get().equals(request.identity().organizationId());
            case WORKSPACE -> id.get().equals(request.identity().workspaceId());
            case AGENT -> request.agentId().filter(id.get()::equals).isPresent();
            case USER -> id.get().equals(request.identity().userId());
            case SESSION -> id.get().equals(request.identity().sessionId());
        };
    }

    private static CredentialScope identified(Level level, UUID id) {
        return new CredentialScope(level, Optional.of(Objects.requireNonNull(id, "id")));
    }
}
