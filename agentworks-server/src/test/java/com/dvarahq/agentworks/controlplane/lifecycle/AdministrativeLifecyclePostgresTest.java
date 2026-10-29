package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.deployment.ReleaseLifecycleService;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseLifecycleCommand;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL authority, propagation, replay, and tamper evidence for issue #79. */
class AdministrativeLifecyclePostgresTest {
    private LifecycleFixture fixture;
    private WorkspaceId workspace;
    private ReleaseLifecycleService releases;

    @BeforeEach
    void setUp() {
        fixture = LifecycleFixture.postgres();
        workspace = fixture.workspace();
        releases = new ReleaseLifecycleService(fixture.tenants, fixture.lifecycle, fixture.idempotencyRecords,
                fixture.clock, fixture.retry, Duration.ofHours(24), LifecycleFixture.TEST_DIGESTS);
    }

    @Test
    void suspendResumeAndDependencyRevocationPropagateAndFenceAdmission() {
        var release = fixture.release(workspace, "administrative-agent", "1.0.0");
        var environment = fixture.environment(workspace, "administrative-environment");
        var promoted = fixture.promote(workspace, environment, "api", release);
        var suspend = command(release.id(), 1, "suspend-" + UUID.randomUUID(), "security review",
                null, null, null);

        var suspended = releases.transition(workspace, "suspend", suspend);
        var replay = releases.transition(workspace, "suspend", suspend);
        assertThat(suspended.value().lifecycle().state()).isEqualTo("SUSPENDED");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value().decisionId()).isEqualTo(suspended.value().decisionId());
        assertThat(fixture.deployments.cohort(workspace, promoted.cohortId()).state()).isEqualTo("SUSPENDED");

        releases.transition(workspace, "resume", command(release.id(), 2, "resume-" + UUID.randomUUID(),
                "review cleared", null, null, null));
        assertThat(fixture.deployments.cohort(workspace, promoted.cohortId()).state()).isEqualTo("ROUTABLE");

        var revoked = releases.transition(workspace, "revoke", command(release.id(), 3,
                "revoke-" + UUID.randomUUID(), "dependency compromised", "DEPENDENCY", "pkg:maven/example@1",
                "sha256:" + "a".repeat(64)));
        assertThat(revoked.value().revocation().subjectKind()).isEqualTo("DEPENDENCY");
        assertThat(fixture.deployments.cohort(workspace, promoted.cohortId()).state()).isEqualTo("REVOKED");
        assertThatThrownBy(() -> fixture.tenants.executeWithoutResult(workspace, () -> releases.requireAdmissible(
                        fixture.lifecycle.findRelease(release.id()).orElseThrow())))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        failure -> assertThat(failure.code()).isEqualTo("RELEASE_REVOKED"));
    }

    @Test
    void runPlaneCannotForgeAuthorityAndImmutableHistoryRejectsOwnerTampering() {
        var release = fixture.release(workspace, "authority-agent", "1.0.0");
        var revoked = releases.transition(workspace, "revoke", command(release.id(), 1,
                "revoke-" + UUID.randomUUID(), "key compromised", "SIGNING_KEY", "key:release-test", null));

        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane",
                "update release_lifecycle set state = 'AVAILABLE' where release_id = ?", release.id()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", "select count(*) from release_revocation"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fixture.jdbc.update("update release_revocation set reason = 'rewritten' where id = ?",
                revoked.value().revocation().id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> fixture.jdbc.update("delete from release_lifecycle_event where release_id = ?",
                release.id())).isInstanceOf(RuntimeException.class);
    }

    private static ReleaseLifecycleCommand command(UUID releaseId, long version, String key, String reason,
            String subjectKind, String subjectIdentity, String evidenceDigest) {
        return new ReleaseLifecycleCommand(releaseId, version, key, "user:administrator", reason, subjectKind,
                subjectIdentity, evidenceDigest);
    }
}
