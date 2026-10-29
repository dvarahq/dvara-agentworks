package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ContinuityAction;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EvidenceKind;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateType;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.NumericComparison;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionEvidence;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionGate;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionPolicyEvaluatorTest {
    private static final UUID RELEASE = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void evaluatesEveryEvidenceGateDeterministicallyAndEnforcesSeparationOfDuties() {
        List<PromotionGate> gates = new ArrayList<>();
        List<PromotionEvidence> evidence = new ArrayList<>();
        for (EvidenceKind kind : EvidenceKind.values()) {
            GateType type = GateType.valueOf(kind.name());
            gates.add(new PromotionGate(type, null, kind, passStatus(kind), null, null, null));
            evidence.add(evidence(kind, passStatus(kind), kind == EvidenceKind.HUMAN_APPROVAL ? "approver" : null,
                    Map.of()));
        }
        gates.add(new PromotionGate(GateType.SEPARATION_OF_DUTIES, null, EvidenceKind.HUMAN_APPROVAL,
                "APPROVED", null, null, null));
        gates.add(new PromotionGate(GateType.COST, null, EvidenceKind.COST, "PASSED", "usd",
                NumericComparison.AT_MOST, new BigDecimal("1.50")));
        evidence.removeIf(item -> item.kind() == EvidenceKind.COST);
        evidence.add(evidence(EvidenceKind.COST, "PASSED", null, Map.of("usd", new BigDecimal("1.25"))));

        PromotionPolicy policy = new PromotionPolicy(gates, null, ContinuityAction.FAIL, false);
        var first = PromotionPolicyEvaluator.evaluate(policy, RELEASE, "promoter", evidence, ignored -> false);
        var replay = PromotionPolicyEvaluator.evaluate(policy, RELEASE, "promoter", evidence, ignored -> false);

        assertThat(first).isEqualTo(replay).allSatisfy(result -> assertThat(result.result()).isEqualTo("PASSED"));
        assertThat(first).allSatisfy(result -> {
            assertThat(result.evidenceId()).isNotNull();
            assertThat(result.evidenceDigest()).startsWith("sha256:");
        });
    }

    @Test
    void refusesMissingFailedMetricAndSameActorEvidence() {
        String subject = "user:10000000-0000-0000-0000-000000000099";
        List<PromotionGate> gates = List.of(
                new PromotionGate(GateType.TEST, null, EvidenceKind.TEST, "PASSED", null, null, null),
                new PromotionGate(GateType.LATENCY, null, EvidenceKind.LATENCY, "PASSED", "p95Ms",
                        NumericComparison.AT_MOST, new BigDecimal("100")),
                new PromotionGate(GateType.SEPARATION_OF_DUTIES, null, EvidenceKind.HUMAN_APPROVAL,
                        "APPROVED", null, null, null));
        List<PromotionEvidence> evidence = List.of(
                evidence(EvidenceKind.LATENCY, "PASSED", null, Map.of("p95Ms", new BigDecimal("101"))),
                evidence(EvidenceKind.HUMAN_APPROVAL, "APPROVED", subject + "/api-token:token-a", Map.of()));

        var results = PromotionPolicyEvaluator.evaluate(
                new PromotionPolicy(gates, null, ContinuityAction.FAIL, false), RELEASE,
                subject + "/api-token:token-b", evidence,
                ignored -> false);

        assertThat(results).extracting(result -> result.result()).containsExactly("FAILED", "FAILED", "FAILED");
    }

    @Test
    void classifiesAllDependencyDriftAndAppliesTheConfiguredExplicitResponse() {
        Map<String, Object> before = manifest("one");
        Map<String, Object> after = manifest("two");

        var current = new java.util.EnumMap<LifecycleModel.DependencyType, Object>(LifecycleModel.DependencyType.class);
        current.putAll(PromotionPolicyEvaluator.dependencies(LifecycleJson.write(after)));
        current.put(LifecycleModel.DependencyType.MCP, Map.of("connection", "unavailable"));
        current.put(LifecycleModel.DependencyType.CHILD, Map.of("identity", "unavailable"));
        var failed = PromotionPolicyEvaluator.continuity(
                new PromotionPolicy(List.of(), null, ContinuityAction.FAIL, false), LifecycleJson.write(before),
                LifecycleJson.write(after), current);
        var degraded = PromotionPolicyEvaluator.continuity(
                new PromotionPolicy(List.of(), null, ContinuityAction.DEGRADE, false), LifecycleJson.write(before),
                LifecycleJson.write(after));

        assertThat(failed.state()).isEqualTo("FAILED");
        assertThat(degraded.state()).isEqualTo("DEGRADED");
        assertThat(failed.drift()).hasSize(7);
        assertThat(failed.drift()).extracting(drift -> drift.type().name())
                .containsExactly("MODEL", "TOOL", "MCP", "SKILL", "POLICY", "CHILD", "RUNTIME");
        assertThat(failed.drift()).allSatisfy(drift -> {
            assertThat(drift.previousDigest()).startsWith("sha256:");
            assertThat(drift.candidateDigest()).startsWith("sha256:");
        });
        var expectedBefore = PromotionPolicyEvaluator.dependencies(LifecycleJson.write(before));
        var expectedAfter = PromotionPolicyEvaluator.dependencies(LifecycleJson.write(after));
        assertThat(failed.drift()).filteredOn(drift -> drift.type() == LifecycleModel.DependencyType.POLICY)
                .singleElement().satisfies(drift -> {
                    assertThat(drift.previousDigest()).isEqualTo(Digests.prefixed(
                            LifecycleJson.write(expectedBefore.get(LifecycleModel.DependencyType.POLICY))));
                    assertThat(drift.candidateDigest()).isEqualTo(Digests.prefixed(
                            LifecycleJson.write(expectedAfter.get(LifecycleModel.DependencyType.POLICY))));
                    assertThat(drift.previousDigest()).isNotEqualTo(drift.candidateDigest());
                });
    }

    @Test
    void openCompatibilityPolicyRecordsPlannedReleaseDriftWithoutDenyingThePromotion() {
        String before = LifecycleJson.write(manifest("one"));
        String after = LifecycleJson.write(manifest("two"));

        var result = PromotionPolicyEvaluator.continuity(PromotionPolicy.open(), before, after);

        assertThat(PromotionPolicy.open().continuityAction()).isEqualTo(ContinuityAction.DEGRADE);
        assertThat(result.state()).isEqualTo("DEGRADED");
        assertThat(result.drift()).isNotEmpty();
    }

    @Test
    void legacyRequestsKeepTheirPreEvidenceFingerprintShapeAndAuthorizationIsNotFingerprinted() {
        var configuration = LifecycleModel.DeploymentConfiguration.shared();
        var legacy = new LifecycleModel.PromotionCommand(UUID.randomUUID(), "api", RELEASE, configuration, 3L,
                "ignored", "operator", "reason");
        Object canonical = DeploymentLifecycleService.withoutKey(legacy);
        assertThat(LifecycleJson.write(canonical)).doesNotContain("evidence", "override", "overrideAuthorized")
                .doesNotContain("idempotencyKey");

        var reference = new LifecycleModel.EvidenceReference(UUID.randomUUID(), EvidenceKind.TEST);
        var override = new LifecycleModel.OverrideRequest("risk accepted", "RISK-1");
        var authorized = new LifecycleModel.PromotionCommand(legacy.environmentId(), "api", RELEASE, configuration,
                3L, "ignored", "operator", "reason", List.of(reference), override, true);
        var revoked = new LifecycleModel.PromotionCommand(legacy.environmentId(), "api", RELEASE, configuration,
                3L, "ignored", "operator", "reason", List.of(reference), override, false);
        assertThat(LifecycleJson.write(DeploymentLifecycleService.withoutKey(authorized)))
                .isEqualTo(LifecycleJson.write(DeploymentLifecycleService.withoutKey(revoked)));
    }

    @Test
    void initialAndSameReleaseChecksComparePinsWithCurrentAvailability() {
        String manifest = LifecycleJson.write(manifest("one"));
        var current = new java.util.EnumMap<LifecycleModel.DependencyType, Object>(
                PromotionPolicyEvaluator.dependencies(manifest));
        current.put(LifecycleModel.DependencyType.MODEL, Map.of("availability", "REVOKED"));
        var policy = new PromotionPolicy(List.of(), null, ContinuityAction.FAIL, false);

        assertThat(PromotionPolicyEvaluator.continuity(policy, null, manifest, current).state()).isEqualTo("FAILED");
        assertThat(PromotionPolicyEvaluator.continuity(policy, manifest, manifest, current).drift())
                .extracting(drift -> drift.type()).containsExactly(LifecycleModel.DependencyType.MODEL);
    }

    @Test
    void legacyMissingOptionalListsStayCanonicalThroughAvailabilityCaptureAndEvaluation() {
        String legacy = LifecycleJson.write(Map.of());
        DependencyContinuityProvider provider = (workspace, manifest) ->
                PromotionPolicyEvaluator.dependencies(manifest);

        var result = PromotionPolicyEvaluator.continuity(PromotionPolicy.open(), legacy, legacy,
                provider.capture(new com.dvarahq.agentworks.domain.tenant.WorkspaceId(UUID.randomUUID()), legacy));

        assertThat(result.state()).isEqualTo("UNCHANGED");
        assertThat(result.drift()).isEmpty();
        assertThat(PromotionPolicyEvaluator.dependencies(legacy).get(LifecycleModel.DependencyType.TOOL))
                .isEqualTo(List.of());
        assertThat(PromotionPolicyEvaluator.dependencies(legacy).get(LifecycleModel.DependencyType.SKILL))
                .isEqualTo(List.of());
    }

    private static String passStatus(EvidenceKind kind) {
        return switch (kind) {
            case HUMAN_APPROVAL -> "APPROVED";
            case RELEASE_EVIDENCE -> "VERIFIED";
            default -> "PASSED";
        };
    }

    private static PromotionEvidence evidence(EvidenceKind kind, String status, String actor,
            Map<String, BigDecimal> metrics) {
        UUID id = UUID.nameUUIDFromBytes(kind.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new PromotionEvidence(id, kind, RELEASE, Digests.prefixed(kind.name()), status, metrics, actor,
                Instant.parse("2026-10-15T00:00:00Z"));
    }

    private static Map<String, Object> manifest(String value) {
        Map<String, Object> manifest = new java.util.LinkedHashMap<>();
        manifest.put("models", Map.of("default", value));
        manifest.put("capabilityPacks", List.of(value));
        manifest.put("skills", List.of(value));
        manifest.put("execution", Map.of("policy", value));
        manifest.put("context", Map.of("policy", value));
        manifest.put("memory", Map.of("policy", value));
        manifest.put("limits", Map.of("maxSteps", value));
        manifest.put("executor", Map.of("type", value, "external", Map.of("identity", value)));
        manifest.put("sandbox", Map.of("image", value));
        return manifest;
    }
}
