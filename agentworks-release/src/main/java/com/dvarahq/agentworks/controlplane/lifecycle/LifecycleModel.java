package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.exposure.ExposureSpec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contracts for the release, environment, deployment, cohort, and routing lifecycle. Shapes are provisional for #35. */
public final class LifecycleModel {
    private LifecycleModel() {}

    public static final String ALLOWED = "ALLOWED";
    public static final String DENIED = "DENIED";

    /** Typed, deterministic promotion gates. */
    public enum GateType {
        /** The release must already have been deployed in the named environment. */
        REQUIRE_PRIOR_ENVIRONMENT,
        /** A change freeze: every promotion into the environment is denied. */
        CHANGE_FREEZE,
        TEST,
        EVALUATION,
        SECURITY,
        COST,
        LATENCY,
        HUMAN_APPROVAL,
        SEPARATION_OF_DUTIES,
        RELEASE_EVIDENCE
    }

    public enum EvidenceKind { TEST, EVALUATION, SECURITY, COST, LATENCY, HUMAN_APPROVAL, RELEASE_EVIDENCE }
    public enum NumericComparison { AT_MOST, AT_LEAST }
    public enum ContinuityAction { FAIL, DEGRADE }
    public enum DependencyType { MODEL, TOOL, MCP, SKILL, POLICY, CHILD, RUNTIME }

    /**
     * A gate is deliberately data-only. Evidence gates match {@code evidenceKind}, optionally require a status, and
     * may compare one numeric metric. The older two-argument form remains the source-compatible prior-environment
     * and change-freeze contract.
     */
    public record PromotionGate(GateType type, String environment, EvidenceKind evidenceKind, String requiredStatus,
            String metric, NumericComparison comparison, BigDecimal threshold) {
        public PromotionGate(GateType type, String environment) {
            this(type, environment, null, null, null, null, null);
        }
    }

    /** Versioned environment promotion policy. {@code drainDeadline} is an ISO-8601 duration or null. */
    public record PromotionPolicy(List<PromotionGate> gates, String drainDeadline, ContinuityAction continuityAction,
            Boolean overridesAllowed) {
        public PromotionPolicy {
            gates = gates == null ? List.of() : List.copyOf(gates);
            continuityAction = continuityAction == null ? ContinuityAction.FAIL : continuityAction;
            overridesAllowed = Boolean.TRUE.equals(overridesAllowed);
        }

        public PromotionPolicy(List<PromotionGate> gates, String drainDeadline) {
            this(gates, drainDeadline, ContinuityAction.FAIL, false);
        }

        public static PromotionPolicy open() {
            // The compatibility policy has no operator-authored gates. Preserve historical promotion behavior while
            // still surfacing and auditing dependency changes; configured policies remain fail-closed by default.
            return new PromotionPolicy(List.of(), null, ContinuityAction.DEGRADE, false);
        }
    }

    /** Content-free, immutable evidence selected from an authoritative provider (for example issue #54's bundle). */
    public record PromotionEvidence(UUID id, EvidenceKind kind, UUID releaseId, String digest, String status,
            Map<String, BigDecimal> metrics, String actor, Instant capturedAt) {
        public PromotionEvidence {
            metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }
    }

    public record EvidenceReference(UUID id, EvidenceKind kind) {}
    public record OverrideRequest(String reason, String ticket) {}
    public record GateResult(PromotionGate gate, String result, String detail, UUID evidenceId, String evidenceDigest) {
        public GateResult(PromotionGate gate, String result, String detail) {
            this(gate, result, detail, null, null);
        }
    }

    public record DependencyDrift(DependencyType type, String previousDigest, String candidateDigest) {}
    public record ContinuityResult(String state, ContinuityAction action, List<DependencyDrift> drift) {
        public ContinuityResult {
            drift = drift == null ? List.of() : List.copyOf(drift);
        }
    }

    /**
     * Deployment content captured in each immutable revision. Credentials are {@code CredentialRef} identifiers and
     * policies are versioned identities; resolved secret values are never accepted or stored.
     */
    public record PlacementConstraints(String securityZone, String dataResidency, String runtimeClass,
            String imageClass) {}

    public record DeploymentConfiguration(String placement, Map<String, String> credentialReferences,
            List<String> policyIdentities, PlacementConstraints placementConstraints, List<ExposureSpec> exposures) {
        public DeploymentConfiguration {
            placement = placement == null ? "SHARED" : placement;
            credentialReferences = credentialReferences == null ? Map.of() : Map.copyOf(credentialReferences);
            policyIdentities = policyIdentities == null ? List.of() : List.copyOf(policyIdentities);
            exposures = exposures == null ? List.of() : List.copyOf(exposures);
        }

        public DeploymentConfiguration(String placement, Map<String, String> credentialReferences,
                List<String> policyIdentities) {
            this(placement, credentialReferences, policyIdentities, null, List.of());
        }

        public DeploymentConfiguration(String placement, Map<String, String> credentialReferences,
                List<String> policyIdentities, PlacementConstraints placementConstraints) {
            this(placement, credentialReferences, policyIdentities, placementConstraints, List.of());
        }

        public static DeploymentConfiguration shared() {
            return new DeploymentConfiguration("SHARED", Map.of(), List.of(), null, List.of());
        }
    }

    public record ReleaseView(UUID id, UUID workspaceId, UUID definitionId, UUID versionId, String name,
            String manifestDigest, Instant createdAt) {}

    public record ReleaseLifecycleView(UUID releaseId, String state, long version, String reason, String actor,
            Instant changedAt) {}

    public record ReleaseRevocationView(UUID id, UUID releaseId, String subjectKind, String subjectIdentity,
            String reason, String evidenceDigest, String actor, Instant occurredAt) {}

    public record ReleaseLifecycleCommand(UUID releaseId, long expectedVersion, String idempotencyKey, String actor,
            String reason, String subjectKind, String subjectIdentity, String evidenceDigest) {}

    public record ReleaseLifecycleResult(UUID decisionId, ReleaseLifecycleView lifecycle,
            ReleaseRevocationView revocation, List<UUID> affectedCohortIds) {
        public ReleaseLifecycleResult {
            affectedCohortIds = affectedCohortIds == null ? List.of() : List.copyOf(affectedCohortIds);
        }
    }

    public record AliasView(String name, UUID releaseId, long version, Instant updatedAt) {}

    public record EnvironmentView(UUID id, UUID workspaceId, String name, String description, long policyVersion,
            PromotionPolicy promotionPolicy, String policyDigest, Instant createdAt) {}

    public record PromotionCommand(UUID environmentId, String deploymentName, UUID releaseId,
            DeploymentConfiguration configuration, Long expectedGeneration, String idempotencyKey, String actor,
            String reason, List<EvidenceReference> evidence, OverrideRequest override, boolean overrideAuthorized) {
        public PromotionCommand {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public PromotionCommand(UUID environmentId, String deploymentName, UUID releaseId,
                DeploymentConfiguration configuration, Long expectedGeneration, String idempotencyKey, String actor,
                String reason) {
            this(environmentId, deploymentName, releaseId, configuration, expectedGeneration, idempotencyKey, actor,
                    reason, List.of(), null, false);
        }
    }

    public record RollbackCommand(UUID deploymentId, UUID releaseId, long expectedGeneration, String idempotencyKey,
            String actor, String reason, List<EvidenceReference> evidence, OverrideRequest override,
            boolean overrideAuthorized) {
        public RollbackCommand {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        public RollbackCommand(UUID deploymentId, UUID releaseId, long expectedGeneration, String idempotencyKey,
                String actor, String reason) {
            this(deploymentId, releaseId, expectedGeneration, idempotencyKey, actor, reason, List.of(), null, false);
        }
    }

    public record CohortCommand(UUID cohortId, long expectedVersion, String idempotencyKey, String actor, String reason) {}

    public record PromotionResult(UUID decisionId, String kind, String outcome, List<GateResult> gates,
            UUID deploymentId, Long generation, UUID revisionId, UUID cohortId, UUID routingGenerationId,
            List<UUID> drainedCohortIds, String inputDigest, OverrideRequest override,
            ContinuityResult continuity) {
        public PromotionResult {
            gates = gates == null ? List.of() : List.copyOf(gates);
            drainedCohortIds = drainedCohortIds == null ? List.of() : List.copyOf(drainedCohortIds);
            continuity = continuity == null
                    ? new ContinuityResult("UNCHANGED", ContinuityAction.FAIL, List.of()) : continuity;
        }

        public PromotionResult(UUID decisionId, String kind, String outcome, List<GateResult> gates,
                UUID deploymentId, Long generation, UUID revisionId, UUID cohortId, UUID routingGenerationId,
                List<UUID> drainedCohortIds) {
            this(decisionId, kind, outcome, gates, deploymentId, generation, revisionId, cohortId,
                    routingGenerationId, drainedCohortIds, null, null,
                    new ContinuityResult("UNCHANGED", ContinuityAction.FAIL, List.of()));
        }
    }

    public record CohortResult(UUID decisionId, UUID cohortId, String state, long transitionVersion) {}

    public record Blocker(String category, String state, long count, Instant oldestSince) {}

    public record RetirementResult(UUID decisionId, UUID cohortId, String outcome, String state, long transitionVersion,
            List<Blocker> blockers) {}

    public record QuiescenceReport(UUID cohortId, String state, long transitionVersion, boolean quiescent,
            List<Blocker> blockers, Instant sourceCutoff, Instant drainDeadline, boolean drainDeadlineExceeded) {}

    /** A command result and whether it was replayed from an earlier request with the same key and fingerprint. */
    public record Idempotent<T>(T value, boolean replayed) {}
}
