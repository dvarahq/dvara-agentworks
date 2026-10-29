package com.dvarahq.agentworks.storage.identity;

import java.time.Instant;
import java.util.UUID;

/** Row values for identities, memberships, sessions, API tokens, and bootstrap state. No row carries a secret. */
public final class IdentityRows {
    private IdentityRows() {}

    /** A user; {@code passwordHash} is an adaptive hash and is omitted from {@link #toString()}. */
    public record UserRow(UUID id, UUID organizationId, String username, String displayName, String organizationRole,
            String identityProvider, String passwordHash, boolean mustChangePassword, String status,
            int failedLoginCount, Instant lockedUntil, Instant createdAt) {
        @Override
        public String toString() {
            return "UserRow[id=" + id + ", organizationId=" + organizationId + ", username=" + username
                    + ", organizationRole=" + organizationRole + ", status=" + status + "]";
        }
    }

    public record MembershipRow(UUID workspaceId, UUID organizationId, UUID subjectId, String role, Instant createdAt) {}

    public record MemberRow(UUID subjectId, String username, String displayName, String role, Instant createdAt) {}

    public record WorkspaceMembershipRow(UUID workspaceId, UUID organizationId, String workspaceName, String role) {}

    public record SessionRow(UUID id, UUID organizationId, UUID userId, UUID workspaceId, Instant createdAt,
            Instant lastSeenAt, Instant expiresAt, Instant revokedAt, String revokedReason) {}

    /**
     * A session or token joined with its user and the user's current membership in the bound workspace, read in
     * one statement when a request authenticates. {@code membershipRole} is {@code null} without a membership.
     */
    public record CredentialGrant(UUID credentialId, UUID organizationId, UUID userId, UUID workspaceId,
            String username, String organizationRole, String userStatus, boolean mustChangePassword,
            String membershipRole, String tokenRole, Instant createdAt, Instant lastSeenAt, Instant expiresAt,
            Instant revokedAt, String digest) {
        @Override
        public String toString() {
            return "CredentialGrant[credentialId=" + credentialId + ", userId=" + userId + ", workspaceId="
                    + workspaceId + "]";
        }
    }

    public record TokenRow(UUID id, String prefix, String name, UUID organizationId, UUID workspaceId, UUID userId,
            String role, Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt, UUID revokedBy) {}

    /** {@code issuedBy} and {@code tokenFile} say where the token's file was written (#177); null before V11. */
    public record BootstrapRow(String state, String tokenDigest, Instant issuedAt, Instant expiresAt,
            Instant completedAt, String issuedBy, String tokenFile) {
        @Override
        public String toString() {
            return "BootstrapRow[state=" + state + ", expiresAt=" + expiresAt + "]";
        }
    }

    /**
     * The first sight of a bootstrap reissue value (#201): {@code targetDigest} is the digest of the token that was live
     * then, the only token the value may replace, or null when none was.
     */
    public record ReissueSighting(Instant seenAt, String seenBy, String targetDigest) {
        @Override
        public String toString() {
            return "ReissueSighting[seenAt=" + seenAt + ", seenBy=" + seenBy + "]";
        }
    }

    /** An audit fact; {@code detail} is a fixed reason code and never request content or credential material. */
    public record AuditEvent(UUID id, Instant occurredAt, String eventType, String outcome, UUID organizationId,
            UUID workspaceId, UUID actorId, String authenticationMethod, UUID credentialId, String targetType,
            UUID targetId, String detail, String remoteAddress) {}
}
