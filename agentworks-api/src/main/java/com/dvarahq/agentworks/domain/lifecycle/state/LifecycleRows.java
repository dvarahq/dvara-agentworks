package com.dvarahq.agentworks.domain.lifecycle.state;

import java.time.Instant;
import java.util.UUID;

/** Neutral lifecycle value records shared by storage adapters, services, and the Run Plane port. */
public final class LifecycleRows {
    private LifecycleRows() {}

    public record ReleaseRow(UUID id, UUID workspaceId, UUID definitionId, UUID versionId, String name,
            String manifestJson, String manifestDigest, Instant createdAt) {}

    /** Immutable signed bundle retained independently of deployment/cohort lifecycle state. */
    public record ReleaseBundleRow(UUID workspaceId, UUID releaseId, String bundleJson, String bundleDigest,
            String manifestDigest, String signingKeyId, Instant signedAt, Instant importedAt) {}

    public record ReleaseLifecycleRow(UUID workspaceId, UUID releaseId, String state, long lifecycleVersion,
            String reason, String actor, Instant changedAt) {}

    public record ReleaseRevocationRow(UUID id, UUID releaseId, String subjectKind, String subjectIdentity,
            String reason, String evidenceDigest, String actor, Instant occurredAt) {}

    public record AliasRow(UUID workspaceId, String name, UUID releaseId, long aliasVersion, Instant updatedAt) {}

    public record AliasHistoryRow(UUID id, String aliasName, long aliasVersion, UUID releaseId, UUID previousReleaseId,
            Instant changedAt) {}

    public record EnvironmentRow(UUID id, UUID workspaceId, String name, String description, long policyVersion,
            String policyJson, String policyDigest, Instant createdAt) {}

    public record DeploymentRow(UUID id, UUID workspaceId, UUID environmentId, UUID definitionId, String name,
            UUID desiredReleaseId, long generation, UUID currentRevisionId, UUID currentRoutingGenerationId,
            Instant createdAt) {}

    public record RevisionRow(UUID id, UUID workspaceId, UUID deploymentId, long generation, UUID releaseId,
            UUID definitionId, UUID environmentId, String placement, String configurationJson, String contentDigest,
            String cause, UUID decisionId, UUID previousRevisionId, Instant createdAt) {}

    public record CohortRow(UUID id, UUID workspaceId, UUID deploymentId, UUID revisionId, String state,
            long transitionVersion, Instant stateChangedAt, Instant drainStartedAt, Instant drainDeadline,
            Instant quiescentSince, Instant retiredAt, Instant createdAt) {}

    public record TransitionRow(UUID id, UUID cohortId, long transitionVersion, String fromState, String toState,
            String cause, UUID decisionId, String actor, String reason, Instant occurredAt) {}

    public record RoutingGenerationRow(UUID id, UUID workspaceId, UUID deploymentId, long generation, String policy,
            UUID decisionId, UUID previousGenerationId, Instant createdAt) {}

    public record RoutingTargetRow(UUID generationId, int ordinal, UUID cohortId, UUID revisionId, int weight) {}

    public record DecisionRow(UUID id, UUID workspaceId, String kind, String outcome, UUID environmentId,
            UUID definitionId, UUID deploymentId, UUID releaseId, UUID cohortId, Long policyVersion, String policyDigest,
            String gateResultsJson, String actor, String reason, UUID revisionId, UUID routingGenerationId,
            Instant createdAt) {}

    public record OutboxRow(UUID id, long sequence, String eventType, UUID decisionId, String payloadJson,
            Instant createdAt) {}

    public record IdempotencyRow(String namespace, String keyDigest, String fingerprintDigest, int digestVersion,
            String resultKind,
            UUID resultId, String resultStatus, String resultJson, Instant createdAt, Instant replayUntil,
            Instant compactedAt) {}

    public record RunRow(UUID id, UUID workspaceId, UUID definitionId, UUID versionId, UUID releaseId,
            UUID deploymentId, UUID environmentId, UUID revisionId, UUID cohortId, String admission,
            UUID routingGenerationId, Integer routingTargetOrdinal, UUID parentRunId, UUID rootRunId,
            String continuationKind, String lifecycle, String idempotencyKeyDigest, Integer idempotencyDigestVersion,
            String requestFingerprint, Instant createdAt) {}

    /** One category of durable work that prevents a cohort from being quiescent. */
    public record QuiescenceBlocker(String category, String state, long count, Instant oldestSince) {}
}
