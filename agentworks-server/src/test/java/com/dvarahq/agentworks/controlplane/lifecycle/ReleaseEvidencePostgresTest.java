package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.deployment.DeploymentLifecycleService;
import com.dvarahq.agentworks.controlplane.deployment.ReleaseBundlePromotionEvidenceProvider;
import com.dvarahq.agentworks.autoconfigure.EvaluationPromotionEvidenceAdapter;
import com.dvarahq.agentworks.autoconfigure.JdbcPromotionApprovalAuthority;
import com.dvarahq.agentworks.controlplane.evidence.EvidenceAnchor;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseAdmissionPolicy;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidence;
import com.dvarahq.agentworks.controlplane.evidence.ReleaseEvidenceAuthority;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseBundleRow;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import com.dvarahq.agentworks.evaluation.EvaluationContracts;
import com.dvarahq.agentworks.evaluation.EvaluationService;
import com.dvarahq.agentworks.evaluation.EvaluatorEngine;
import com.dvarahq.agentworks.storage.evaluation.JdbcEvaluationRepository;
import com.dvarahq.agentworks.spec.AgentSpecValidationException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL proof for signed evidence persistence, admission, anchoring, and plane separation (issue #54). */
class ReleaseEvidencePostgresTest {
    private static LifecycleFixture fixture;

    @BeforeAll
    static void setUp() {
        fixture = LifecycleFixture.postgres();
    }

    @Test
    void signedBundleRoundTripsAnchorsAndIsRequiredByDeploymentAdmission() throws Exception {
        WorkspaceId workspace = fixture.workspace();
        var release = fixture.release(workspace, "signed-agent", "1.0.0");
        var environment = fixture.environment(workspace, "signed-prod");
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var trust = new ReleaseEvidence.TrustPolicy(Map.of("release-test", pair.getPublic().getEncoded()), Set.of());
        EvidenceAnchor anchor = new EvidenceAnchor() {
            @Override public Receipt submit(String digest) {
                return new Receipt("test-log", digest, "receipt-1", Instant.parse("2026-10-15T12:00:00Z"));
            }
            @Override public boolean verify(Receipt receipt) { return receipt.receipt().equals("receipt-1"); }
        };
        var evidence = new ReleaseEvidenceAuthority(fixture.tenants, fixture.lifecycle, fixture.clock,
                new ReleaseEvidence.Ed25519Signer("release-test", pair.getPrivate().getEncoded(),
                        pair.getPublic().getEncoded()), trust, anchor);
        var guarded = new DeploymentLifecycleService(fixture.tenants, fixture.lifecycle, fixture.idempotencyRecords,
                fixture.clock, fixture.retry, Duration.ofHours(24), LifecycleFixture.TEST_DIGESTS, evidence);

        var command = new LifecycleModel.PromotionCommand(environment.id(), "signed-deployment", release.id(),
                LifecycleModel.DeploymentConfiguration.shared(), null, null, "tester", "signed admission");
        assertThatThrownBy(() -> guarded.promote(workspace, command))
                .isInstanceOf(LifecycleConflictException.class)
                .extracting(value -> ((LifecycleConflictException) value).code())
                .isEqualTo("SIGNED_EVIDENCE_REQUIRED");

        var signed = evidence.sign(workspace, release.id());
        var embeddedAbom = JsonMapper.builder().build().readTree(signed.payload().manifest()).path("abom");
        assertThat(JsonMapper.builder().build().treeToValue(embeddedAbom, ReleaseEvidence.Abom.class))
                .isEqualTo(signed.payload().abom());
        assertThat(evidence.exportBundle(workspace, release.id()).canonicalJson()).isEqualTo(signed.canonicalJson());
        assertThat(evidence.importBundle(workspace, signed.canonicalJson()).digest()).isEqualTo(signed.digest());
        assertThat(evidence.verify(signed.canonicalJson()).trusted()).isTrue();
        assertThat(evidence.anchor(workspace, release.id()).bundleDigest()).isEqualTo(signed.digest());
        assertThat(guarded.promote(workspace, command).value().outcome()).isEqualTo(LifecycleModel.ALLOWED);

        // A valid signature over identical manifest bytes is still bound to one release identity.
        var other = fixture.releases.createRelease(workspace, release.versionId(), "signed-agent-copy");
        fixture.tenants.execute(workspace, () -> {
            fixture.lifecycle.insertReleaseBundle(new ReleaseBundleRow(workspace.value(), other.id(),
                    signed.canonicalJson(), signed.digest(), signed.payload().manifestDigest(),
                    signed.signature().keyId(), signed.signature().signedAt(), fixture.clock.now()));
            return null;
        });
        assertThatThrownBy(() -> evidence.exportBundle(workspace, other.id()))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("RELEASE_BUNDLE_MISMATCH"));

        var revokedHistory = new ReleaseEvidenceAuthority(fixture.tenants, fixture.lifecycle, fixture.clock, null,
                new ReleaseEvidence.TrustPolicy(Map.of("release-test", pair.getPublic().getEncoded()),
                        Set.of("release-test")), anchor);
        assertThat(revokedHistory.exportBundle(workspace, release.id()).digest()).isEqualTo(signed.digest());
        assertThat(revokedHistory.sign(workspace, release.id()).digest()).isEqualTo(signed.digest());
        assertThat(revokedHistory.anchor(workspace, release.id()).bundleDigest()).isEqualTo(signed.digest());
        assertThat(revokedHistory.verify(signed.canonicalJson()).code()).isEqualTo("SIGNING_KEY_REVOKED");

        // Portable microsecond timestamps survive PostgreSQL without changing signed bytes or admission identity.
        var importedRelease = fixture.releases.createRelease(workspace, release.versionId(), "signed-agent-imported");
        String importedManifest = fixture.releases.manifest(workspace, importedRelease.id());
        var importedPayload = new ReleaseEvidence.Payload(ReleaseEvidence.BUNDLE_VERSION, importedRelease.id(),
                importedManifest, importedRelease.manifestDigest(), java.util.List.of(), null);
        var portable = new ReleaseEvidence.Ed25519Signer("release-test", pair.getPrivate().getEncoded(),
                pair.getPublic().getEncoded()).sign(importedPayload,
                        Instant.parse("2026-10-15T12:00:00.123456Z"));
        assertThat(evidence.importBundle(workspace, portable.canonicalJson()).canonicalJson())
                .isEqualTo(portable.canonicalJson());
        assertThat(evidence.exportBundle(workspace, importedRelease.id()).canonicalJson())
                .isEqualTo(portable.canonicalJson());
        var importedCommand = new LifecycleModel.PromotionCommand(environment.id(), "imported-deployment",
                importedRelease.id(), LifecycleModel.DeploymentConfiguration.shared(), null, null, "tester",
                "portable microsecond admission");
        assertThat(guarded.promote(workspace, importedCommand).value().outcome()).isEqualTo(LifecycleModel.ALLOWED);

        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane",
                "select count(*) from release_evidence_bundle"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fixture.asRole("agentworks_control_plane",
                "update release_evidence_bundle set signing_key_id = 'rewritten' where release_id = ?", release.id()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void promotionReleaseEvidenceGateConsumesTheTrustedStoredBundle() throws Exception {
        WorkspaceId workspace = fixture.workspace();
        var release = fixture.release(workspace, "promotion-bundle-agent", "1.0.0");
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authority = authority(pair, Set.of());
        var signed = authority.sign(workspace, release.id());
        var gate = new LifecycleModel.PromotionGate(LifecycleModel.GateType.RELEASE_EVIDENCE, null,
                LifecycleModel.EvidenceKind.RELEASE_EVIDENCE, "VERIFIED", null, null, null);
        var environment = fixture.environments.create(workspace, "bundle-gated", null,
                new LifecycleModel.PromotionPolicy(List.of(gate), null));
        var service = gatedService(authority);
        var command = new LifecycleModel.PromotionCommand(environment.id(), "bundle-gated", release.id(), null, 0L,
                "bundle-gate-1", "promoter", "trusted bundle",
                List.of(new LifecycleModel.EvidenceReference(release.id(),
                        LifecycleModel.EvidenceKind.RELEASE_EVIDENCE)), null, false);

        var promoted = service.promote(workspace, command).value();

        assertThat(promoted.outcome()).isEqualTo(LifecycleModel.ALLOWED);
        assertThat(promoted.gates()).singleElement().satisfies(result -> {
            assertThat(result.result()).isEqualTo("PASSED");
            assertThat(result.evidenceId()).isEqualTo(release.id());
            assertThat(result.evidenceDigest()).isEqualTo(signed.digest());
        });
    }

    @Test
    void promotionReleaseEvidenceGateFailsClosedForUnavailableRevokedAndTamperedBundles() throws Exception {
        WorkspaceId workspace = fixture.workspace();
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var trusted = authority(pair, Set.of());

        var unavailable = fixture.release(workspace, "unavailable-bundle-agent", "1.0.0");
        var unavailableEnvironment = releaseEvidenceEnvironment(workspace, "unavailable-bundle");
        assertPromotionConflict(gatedService(trusted), workspace, unavailableEnvironment.id(), unavailable.id(),
                "unavailable-bundle", "SIGNED_EVIDENCE_REQUIRED");

        var revoked = fixture.release(workspace, "revoked-bundle-agent", "1.0.0");
        trusted.sign(workspace, revoked.id());
        var revokedEnvironment = releaseEvidenceEnvironment(workspace, "revoked-bundle");
        assertPromotionConflict(gatedService(authority(pair, Set.of("release-test"))), workspace,
                revokedEnvironment.id(), revoked.id(), "revoked-bundle", "SIGNING_KEY_REVOKED");

        var tampered = fixture.release(workspace, "tampered-bundle-agent", "1.0.0");
        var payload = new ReleaseEvidence.Payload(ReleaseEvidence.BUNDLE_VERSION, tampered.id(),
                fixture.releases.manifest(workspace, tampered.id()), tampered.manifestDigest(), List.of(), null);
        var bundle = new ReleaseEvidence.Ed25519Signer("release-test", pair.getPrivate().getEncoded(),
                pair.getPublic().getEncoded()).sign(payload, Instant.parse("2026-10-15T12:00:00Z"));
        String changed = bundle.canonicalJson().replace("release-test", "release-tesx");
        fixture.tenants.execute(workspace, () -> {
            fixture.lifecycle.insertReleaseBundle(new ReleaseBundleRow(workspace.value(), tampered.id(), changed,
                    bundle.digest(), tampered.manifestDigest(), bundle.signature().keyId(),
                    bundle.signature().signedAt(), fixture.clock.now()));
            return null;
        });
        var tamperedEnvironment = releaseEvidenceEnvironment(workspace, "tampered-bundle");
        assertPromotionConflict(gatedService(trusted), workspace, tamperedEnvironment.id(), tampered.id(),
                "tampered-bundle", "BUNDLE_INTEGRITY_FAILED");
    }

    @Test
    void productionWorkflowCreatesReportThenReleaseThenPromotesWithTheImmutableSpecBinding() throws Exception {
        WorkspaceId workspace = fixture.workspace();
        UUID versionId = fixture.version(workspace, "evaluated-agent", "1.0.0");
        String specDigest = fixture.tenants.execute(workspace, () -> fixture.jdbc.queryForObject(
                "select spec_digest from agent_version where workspace_id = ? and id = ?", String.class,
                workspace.value(), versionId));
        var evaluations = new EvaluationService(new JdbcEvaluationRepository(fixture.jdbc, fixture.tenants),
                new EvaluatorEngine(List.of()), java.time.Clock.systemUTC());
        var json = JsonMapper.builder().build();
        var dataset = evaluations.createDataset(workspace.value(), "pre-release", 1, false, null,
                List.of(new EvaluationContracts.Case("one", json.readTree("1"), json.readTree("1"), Map.of())));
        var exact = evaluations.createEvaluator(workspace.value(), "release-test", 1,
                EvaluationContracts.EvaluatorType.EXACT, json.readTree("{}"));
        var observation = new EvaluationContracts.Observation("one", json.readTree("1"), List.of(), true, 5,
                new BigDecimal("0.01"), "captured:test", "sha256:" + "8".repeat(64));
        var report = evaluations.start(workspace.value(), new EvaluationContracts.StartRequest(dataset.id(),
                List.of(exact.id()), "spec:" + specDigest, specDigest, Map.of("one", observation),
                new EvaluationContracts.Budget(BigDecimal.ONE, 100, Duration.ofSeconds(5), 1), "pre-release-1"));
        var reference = new ReleaseEvidence.EvidenceReference(ReleaseEvidence.EvidenceKind.EVALUATION,
                "evaluation:" + report.run().id(), report.digest());
        var release = fixture.releases.createRelease(workspace, versionId, "evaluated-agent-1.0.0",
                List.of(reference));
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authority = authority(pair, Set.of());
        authority.sign(workspace, release.id());
        var gate = new LifecycleModel.PromotionGate(LifecycleModel.GateType.TEST, null,
                LifecycleModel.EvidenceKind.TEST, "PASSED", null, null, null);
        var environment = fixture.environments.create(workspace, "evaluated-production", null,
                new LifecycleModel.PromotionPolicy(List.of(gate), null));
        var provider = new ReleaseBundlePromotionEvidenceProvider(authority,
                new EvaluationPromotionEvidenceAdapter(evaluations));
        var service = new DeploymentLifecycleService(fixture.tenants, fixture.lifecycle, fixture.idempotencyRecords,
                fixture.clock, fixture.retry, Duration.ofHours(24), LifecycleFixture.TEST_DIGESTS,
                ReleaseAdmissionPolicy.permissive(), provider);

        var result = service.promote(workspace, new LifecycleModel.PromotionCommand(environment.id(), "evaluated",
                release.id(), null, 0L, "evaluated-promotion-1", "user:promoter", "verified report",
                List.of(new LifecycleModel.EvidenceReference(report.run().id(), LifecycleModel.EvidenceKind.TEST)),
                null, false)).value();

        assertThat(result.outcome()).isEqualTo(LifecycleModel.ALLOWED);
        assertThat(result.gates()).singleElement().satisfies(value -> {
            assertThat(value.evidenceId()).isEqualTo(report.run().id());
            assertThat(value.evidenceDigest()).isEqualTo(report.digest());
        });
    }

    @Test
    void promotionApprovalIsExplicitAuthenticatedAndBoundToTheCurrentPolicyAndRelease() throws Exception {
        WorkspaceId workspace = fixture.workspace();
        var release = fixture.release(workspace, "approval-agent", "1.0.0");
        var gate = new LifecycleModel.PromotionGate(LifecycleModel.GateType.HUMAN_APPROVAL, null,
                LifecycleModel.EvidenceKind.HUMAN_APPROVAL, "APPROVED", null, null, null);
        var environment = fixture.environments.create(workspace, "approval-production", null,
                new LifecycleModel.PromotionPolicy(List.of(gate), null));
        var approvals = new JdbcPromotionApprovalAuthority(fixture.jdbc, fixture.tenants, fixture.clock);
        assertThatThrownBy(() -> approvals.request(workspace, environment.id(), environment.policyDigest(), null,
                "user:requester")).isInstanceOf(AgentSpecValidationException.class);
        assertThatThrownBy(() -> approvals.request(workspace, environment.id(), environment.policyDigest(),
                UUID.randomUUID(), "user:requester")).isInstanceOf(LifecycleNotFoundException.class);
        WorkspaceId otherWorkspace = fixture.workspace();
        var otherRelease = fixture.release(otherWorkspace, "other-tenant-agent", "1.0.0");
        assertThatThrownBy(() -> approvals.request(workspace, environment.id(), environment.policyDigest(),
                otherRelease.id(), "user:requester")).isInstanceOf(LifecycleNotFoundException.class);
        var requested = approvals.request(workspace, environment.id(), environment.policyDigest(), release.id(),
                "user:requester");
        assertThat(approvals.resolve(workspace, environment.id(), environment.policyDigest(), release.id(),
                requested.id())).isEmpty();
        var approved = approvals.approve(workspace, requested.id(), "user:approver/api-token:redacted");
        assertThat(approvals.approve(workspace, requested.id(), "user:second-approver")).isEqualTo(approved);
        assertThatThrownBy(() -> fixture.tenants.executeWithoutResult(workspace, () -> fixture.jdbc.update(
                "update promotion_approval set policy_digest = ? where workspace_id = ? and id = ?",
                "sha256:" + "0".repeat(64), workspace.value(), approved.id())))
                .hasMessageContaining("promotion approvals allow only");
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var authority = authority(pair, Set.of());
        authority.sign(workspace, release.id());
        var provider = new ReleaseBundlePromotionEvidenceProvider(authority,
                com.dvarahq.agentworks.controlplane.deployment.EvaluationPromotionEvidenceSource.NONE, approvals);
        var service = new DeploymentLifecycleService(fixture.tenants, fixture.lifecycle, fixture.idempotencyRecords,
                fixture.clock, fixture.retry, Duration.ofHours(24), LifecycleFixture.TEST_DIGESTS,
                ReleaseAdmissionPolicy.permissive(), provider);

        assertThat(approvals.resolve(workspace, UUID.randomUUID(), environment.policyDigest(), release.id(),
                approved.id())).isEmpty();
        assertThat(approvals.resolve(workspace, environment.id(), environment.policyDigest(), release.id(),
                approved.id())).get().extracting(LifecycleModel.PromotionEvidence::actor)
                .isEqualTo("user:approver/api-token:redacted");
        var promoted = service.promote(workspace, new LifecycleModel.PromotionCommand(environment.id(), "approved",
                release.id(), null, 0L, "approval-promotion-1", "user:promoter", "explicit approval",
                List.of(new LifecycleModel.EvidenceReference(approved.id(),
                        LifecycleModel.EvidenceKind.HUMAN_APPROVAL)), null, false)).value();
        assertThat(promoted.outcome()).isEqualTo(LifecycleModel.ALLOWED);
        assertThat(promoted.gates()).singleElement().satisfies(value ->
                assertThat(value.evidenceId()).isEqualTo(approved.id()));
    }

    private static ReleaseEvidenceAuthority authority(java.security.KeyPair pair, Set<String> revoked) {
        return new ReleaseEvidenceAuthority(fixture.tenants, fixture.lifecycle, fixture.clock,
                new ReleaseEvidence.Ed25519Signer("release-test", pair.getPrivate().getEncoded(),
                        pair.getPublic().getEncoded()),
                new ReleaseEvidence.TrustPolicy(Map.of("release-test", pair.getPublic().getEncoded()), revoked),
                EvidenceAnchor.none());
    }

    private static DeploymentLifecycleService gatedService(ReleaseEvidenceAuthority authority) {
        return new DeploymentLifecycleService(fixture.tenants, fixture.lifecycle, fixture.idempotencyRecords,
                fixture.clock, fixture.retry, Duration.ofHours(24), LifecycleFixture.TEST_DIGESTS,
                ReleaseAdmissionPolicy.permissive(), new ReleaseBundlePromotionEvidenceProvider(authority));
    }

    private static LifecycleModel.EnvironmentView releaseEvidenceEnvironment(WorkspaceId workspace, String name) {
        var gate = new LifecycleModel.PromotionGate(LifecycleModel.GateType.RELEASE_EVIDENCE, null,
                LifecycleModel.EvidenceKind.RELEASE_EVIDENCE, "VERIFIED", null, null, null);
        return fixture.environments.create(workspace, name, null,
                new LifecycleModel.PromotionPolicy(List.of(gate), null));
    }

    private static void assertPromotionConflict(DeploymentLifecycleService service, WorkspaceId workspace,
            java.util.UUID environmentId, java.util.UUID releaseId, String deployment, String code) {
        var command = new LifecycleModel.PromotionCommand(environmentId, deployment, releaseId, null, 0L, null,
                "promoter", "fail closed",
                List.of(new LifecycleModel.EvidenceReference(releaseId,
                        LifecycleModel.EvidenceKind.RELEASE_EVIDENCE)), null, false);
        assertThatThrownBy(() -> service.promote(workspace, command))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo(code));
        java.util.Optional<?> remaining = fixture.tenants.execute(workspace,
                () -> fixture.lifecycle.lockDeploymentByName(environmentId, deployment));
        assertThat(remaining).isEmpty();
    }
}
