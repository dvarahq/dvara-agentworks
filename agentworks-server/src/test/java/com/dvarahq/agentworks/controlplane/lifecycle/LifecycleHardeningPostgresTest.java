package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.DeploymentConfiguration;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.GateType;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Idempotent;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionGate;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionPolicy;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RollbackCommand;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dvarahq.agentworks.controlplane.lifecycle.PromotionLifecyclePostgresTest.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Issue #155 hardening of the #31 lifecycle on PostgreSQL. */
class LifecycleHardeningPostgresTest {
    private LifecycleFixture fixture;
    private WorkspaceId workspace;
    private EnvironmentView environment;
    private ReleaseView release;

    @BeforeEach
    void setUp() {
        fixture = LifecycleFixture.postgres();
        workspace = fixture.workspace();
        environment = fixture.environment(workspace, "prod");
        release = fixture.release(workspace, "agent", "1.0.0");
    }

    @Test
    void theFenceRunsAsADedicatedOwnerThatPublicCannotExecute() {
        assertThat(fixture.jdbc.queryForObject("select proowner::regrole::text from pg_proc where proname = 'agentworks_fence_run'",
                String.class)).isEqualTo("agentworks_fence_owner");
        assertThat(fixture.jdbc.queryForObject("select rolcanlogin from pg_roles where rolname = 'agentworks_fence_owner'",
                Boolean.class)).isFalse();
        for (String role : List.of("public", "agentworks_run_plane", "agentworks_control_plane")) {
            assertThat(fixture.jdbc.queryForObject("select has_function_privilege(?, 'agentworks_fence_run()', 'EXECUTE')",
                    Boolean.class, role)).as(role).isFalse();
        }
    }

    @Test
    void separateLoginRolesCarryControlPlaneAndRunPlaneAuthority() {
        var database = LifecycleFixture.postgresDatabase();
        fixture.jdbc.execute("do $$ begin "
                + "if not exists (select 1 from pg_roles where rolname = 'cp_login') then "
                + "create role cp_login login password 'cp' in role agentworks_control_plane; end if; "
                + "if not exists (select 1 from pg_roles where rolname = 'rp_login') then "
                + "create role rp_login login password 'rp' in role agentworks_run_plane; end if; end $$");
        var control = new LifecycleFixture(new DriverManagerDataSource(database.jdbcUrl(), "cp_login", "cp"),
                LifecycleRetry.standard(), Duration.ofHours(24));
        var run = new LifecycleFixture(new DriverManagerDataSource(database.jdbcUrl(), "rp_login", "rp"),
                LifecycleRetry.standard(), Duration.ofHours(24));

        var controlRelease = control.release(workspace, "planes", "1.0.0");
        PromotionResult promoted = control.promote(workspace, environment, "planes", controlRelease);
        var admitted = run.admissions.admitRoot(workspace, promoted.deploymentId(), "plane-key", Map.of("q", 1)).run();

        assertThat(admitted.revisionId()).isEqualTo(promoted.revisionId());
        assertThatThrownBy(() -> run.promote(workspace, environment, "planes", controlRelease))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> control.admissions.admitRoot(workspace, promoted.deploymentId(), null, Map.of()))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> run.jdbc.queryForList("select * from lifecycle_decision"))
                .isInstanceOf(DataAccessException.class).hasStackTraceContaining("permission denied");
    }

    @Test
    void idempotencyStoresOnlyVersionedPurposeSeparatedHmacDigests() {
        promote(fixture, "hmac-key", "first");

        Map<String, Object> record = fixture.jdbc.queryForMap("select key_digest, fingerprint_digest, digest_version "
                + "from lifecycle_idempotency where workspace_id = ?", workspace.value());
        assertThat(record.get("digest_version")).isEqualTo(1);
        assertThat((String) record.get("key_digest")).hasSize(64)
                .isNotEqualTo(Digests.sha256Hex("deployment.promote\nhmac-key"));
        assertThat(record.get("fingerprint_digest")).isNotEqualTo(record.get("key_digest"));
    }

    @Test
    void rotatedKeysKeepReplayingAndARetiredKeyVersionFailsClosed() {
        var original = promote(fixture, "rotating", "first").value();
        var rotated = new LifecycleFixture(fixture.dataSource, LifecycleRetry.standard(), Duration.ofHours(24),
                LifecycleFixture.digests(Map.of(1, 'a', 2, 'b'), 2));
        var retired = new LifecycleFixture(fixture.dataSource, LifecycleRetry.standard(), Duration.ofHours(24),
                LifecycleFixture.digests(Map.of(2, 'b'), 2));

        var replayed = promote(rotated, "rotating", "first");
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.value()).isEqualTo(original);
        var fresh = promote(rotated, "rotated-key", "second");
        assertThat(fixture.jdbc.queryForObject("select digest_version from lifecycle_idempotency where result_id = ?",
                Integer.class, fresh.value().decisionId())).isEqualTo(2);
        assertThat(promote(retired, "rotated-key", "second").replayed()).isTrue();

        // Records under version 1 can no longer be matched, so neither their keys nor unseen keys may execute.
        long decisions = fixture.count("select count(*) from lifecycle_decision where workspace_id = ?", workspace.value());
        for (String key : List.of("rotating", "never-seen")) {
            assertThatThrownBy(() -> promote(retired, key, "first"))
                    .isInstanceOfSatisfying(LifecycleConflictException.class,
                            conflict -> assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_VERSION_RETIRED"));
        }
        PromotionResult promoted = fixture.promote(workspace, environment, "runs", release);
        fixture.admissions.admitRoot(workspace, promoted.deploymentId(), "run-v1", Map.of());
        assertThatThrownBy(() -> retired.admissions.admitRoot(workspace, promoted.deploymentId(), "run-v2", Map.of()))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_KEY_VERSION_RETIRED"));
        assertThat(fixture.count("select count(*) from lifecycle_decision where workspace_id = ?", workspace.value()))
                .isEqualTo(decisions + 1);
    }

    @Test
    void aRollingKeyRotationNeverExecutesOneKeyTwice() throws Exception {
        var oldNode = new LifecycleFixture(fixture.dataSource, LifecycleRetry.standard(), Duration.ofHours(24),
                LifecycleFixture.digests(Map.of(1, 'a'), 1));
        var newNode = new LifecycleFixture(fixture.dataSource, LifecycleRetry.standard(), Duration.ofHours(24),
                LifecycleFixture.digests(Map.of(1, 'a', 2, 'b'), 2));
        PromotionResult promoted = fixture.promote(workspace, environment, "runs", release);

        List<Object> promotions = race(8, index -> promote(index % 2 == 0 ? oldNode : newNode, "rolling", "first"));
        List<Object> admissions = race(8, index -> (index % 2 == 0 ? oldNode : newNode).admissions
                .admitRoot(workspace, promoted.deploymentId(), "rolling-run", Map.of("q", 1)));

        // Old-version nodes that cannot recompute a version-2 record fail closed rather than executing again.
        for (List<Object> outcomes : List.of(promotions, admissions)) {
            assertThat(outcomes).allSatisfy(outcome -> {
                if (outcome instanceof RuntimeException failure) {
                    assertThat(failure).hasMessageContaining("retired HMAC key version");
                }
            });
        }
        assertThat(fixture.count("select count(*) from lifecycle_idempotency where workspace_id = ? "
                + "and namespace = 'deployment.promote'", workspace.value())).isOne();
        assertThat(fixture.count("select count(*) from lifecycle_decision where workspace_id = ? and deployment_id <> ?",
                workspace.value(), promoted.deploymentId())).isOne();
        assertThat(fixture.count("select count(*) from agent_run where workspace_id = ? and idempotency_key_digest is not null",
                workspace.value())).isOne();
    }

    @Test
    void legacyUnkeyedDigestsFromIssue31StillReplay() {
        var original = promote(fixture, "legacy-key", "first").value();
        String canonical = LifecycleJson.write(new LegacyPromotionFingerprint(environment.id(), "api", release.id(),
                null, null, null, "tester", "first"));
        fixture.bypassFence("update lifecycle_idempotency set key_digest = ?, fingerprint_digest = ?, digest_version = 0 "
                + "where workspace_id = ?", Digests.sha256Hex("deployment.promote\nlegacy-key"), Digests.sha256Hex(canonical),
                workspace.value());

        var replayed = promote(fixture, "legacy-key", "first");

        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.value()).isEqualTo(original);
        assertThatThrownBy(() -> promote(fixture, "legacy-key", "changed"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void idempotencyKeysAreRefusedWithoutConfiguredHmacKeys() {
        var unkeyed = new LifecycleFixture(fixture.dataSource, LifecycleRetry.standard(), Duration.ofHours(24), null);
        PromotionResult promoted = unkeyed.promote(workspace, environment, "api", release);

        assertThatThrownBy(() -> promote(unkeyed, "any-key", "first"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_UNAVAILABLE"));
        assertThatThrownBy(() -> unkeyed.admissions.admitRoot(workspace, promoted.deploymentId(), "any-key", Map.of()))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_UNAVAILABLE"));
        assertThat(fixture.count("select count(*) from lifecycle_idempotency where workspace_id = ?", workspace.value())).isZero();
    }

    @Test
    void sameKeyRacesReplayTheWinnerEvenWhenItAdvancedTheDeployment() throws Exception {
        fixture.promote(workspace, environment, "api", release);
        var next = fixture.release(workspace, "agent", "2.0.0");

        List<Object> outcomes = race(8, index -> fixture.deployments.promote(workspace, new PromotionCommand(
                environment.id(), "api", next.id(), null, 1L, "advance-once", "tester", "advance")));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(Idempotent.class));
        assertThat(outcomes.stream().filter(outcome -> !((Idempotent<?>) outcome).replayed())).hasSize(1);
        assertThat(outcomes.stream().map(outcome -> ((Idempotent<?>) outcome).value()).distinct()).hasSize(1);
        assertThat(fixture.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isEqualTo(2);
    }

    @Test
    void runFingerprintsAreComputedFromTheCanonicalRequest() {
        PromotionResult promoted = fixture.promote(workspace, environment, "api", release);

        var first = fixture.admissions.admitRoot(workspace, promoted.deploymentId(), "canonical",
                Map.of("b", 2, "a", Map.of("y", 1, "x", 0)));
        var reordered = fixture.admissions.admitRoot(workspace, promoted.deploymentId(), "canonical",
                new java.util.LinkedHashMap<>(Map.of("a", new java.util.TreeMap<>(Map.of("x", 0, "y", 1)), "b", 2)));

        assertThat(reordered.replayed()).isTrue();
        assertThat(reordered.run().id()).isEqualTo(first.run().id());
        assertThat(first.run().idempotencyDigestVersion()).isEqualTo(1);
        assertThatThrownBy(() -> fixture.admissions.admitRoot(workspace, promoted.deploymentId(), "canonical",
                Map.of("b", 3, "a", Map.of("y", 1, "x", 0))))
                .isInstanceOfSatisfying(RunAdmissionRejectedException.class,
                        rejected -> assertThat(rejected.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rollbackUnderAChangeFreezeIsDeniedAndRecorded() {
        fixture.promote(workspace, environment, "api", release);
        PromotionResult second = fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));
        fixture.environments.updatePolicy(workspace, environment.id(), 1,
                new PromotionPolicy(List.of(new PromotionGate(GateType.CHANGE_FREEZE, null)), null));

        PromotionResult rollback = fixture.deployments.rollback(workspace, new RollbackCommand(second.deploymentId(),
                release.id(), 2, null, "tester", "incident")).value();

        assertThat(rollback.outcome()).isEqualTo("DENIED");
        assertThat(rollback.gates()).singleElement().satisfies(gate -> assertThat(gate.result()).isEqualTo("FAILED"));
        assertThat(fixture.deployments.decision(workspace, rollback.decisionId()).kind()).isEqualTo("ROLLBACK");
        assertThat(fixture.deployments.deployment(workspace, second.deploymentId()).generation()).isEqualTo(2);
        assertThat(fixture.count("select count(*) from deployment_revision where deployment_id = ?", second.deploymentId())).isEqualTo(2);
    }

    private Idempotent<PromotionResult> promote(LifecycleFixture target, String key, String reason) {
        return target.deployments.promote(workspace, new PromotionCommand(environment.id(), "api", release.id(), null,
                null, key, "tester", reason));
    }

    /** Exact pre-#55 request shape used to create digest-version-zero fixture rows. */
    private record LegacyPromotionFingerprint(UUID environmentId, String deploymentName, UUID releaseId,
            DeploymentConfiguration configuration, Long expectedGeneration, String idempotencyKey, String actor,
            String reason) {}
}
