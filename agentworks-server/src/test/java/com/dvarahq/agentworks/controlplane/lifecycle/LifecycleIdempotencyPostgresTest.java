package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.ReleaseView;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.dvarahq.agentworks.controlplane.lifecycle.PromotionLifecyclePostgresTest.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scoped lifecycle-command idempotency, compaction tombstones, namespace retirement, and restore (ADR-027). */
class LifecycleIdempotencyPostgresTest {
    private static final String PERSONAL = "alice@example.com";
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
    void matchingRetryReplaysTheOriginalResultAndMismatchConflictsBeforeMutation() {
        var first = promote(fixture, "promote-1", "first");
        var replay = promote(fixture, "promote-1", "first");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(first.value());
        assertThatThrownBy(() -> promote(fixture, "promote-1", "different reason"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(fixture.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isOne();
        assertThat(fixture.count("select count(*) from lifecycle_decision where workspace_id = ?", workspace.value())).isOne();
    }

    @Test
    void retryAfterPromotionAdvancedStillReturnsTheOriginalResult() {
        var original = promote(fixture, "promote-1", "first").value();
        fixture.promote(workspace, environment, "api", fixture.release(workspace, "agent", "2.0.0"));

        var replay = promote(fixture, "promote-1", "first");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value().revisionId()).isEqualTo(original.revisionId());
        assertThat(fixture.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isEqualTo(2);
    }

    @Test
    void concurrentRequestsWithOneKeyProduceOneMutation() throws Exception {
        List<Object> outcomes = race(8, index -> promote(fixture, "same-key", "concurrent"));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(LifecycleModel.Idempotent.class));
        assertThat(outcomes.stream().map(outcome -> ((LifecycleModel.Idempotent<?>) outcome).value()).distinct()).hasSize(1);
        assertThat(outcomes.stream().filter(outcome -> !((LifecycleModel.Idempotent<?>) outcome).replayed())).hasSize(1);
        assertThat(fixture.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isOne();
    }

    @Test
    void cohortCommandsAreIdempotentToo() {
        PromotionResult promotion = promote(fixture, "p", "first").value();
        var first = fixture.deployments.suspend(workspace, new CohortCommand(promotion.cohortId(), 2, "suspend-1", "tester", "x"));
        var replay = fixture.deployments.suspend(workspace, new CohortCommand(promotion.cohortId(), 2, "suspend-1", "tester", "x"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.value()).isEqualTo(first.value());
        assertThat(fixture.count("select count(*) from cohort_transition where cohort_id = ?", promotion.cohortId())).isEqualTo(3);
    }

    @Test
    void compactedResultReturnsTypedExpiryWithoutMutationAndTheKeyIsNeverReusable() {
        LifecycleFixture shortWindow = LifecycleFixture.postgres(LifecycleRetry.standard(), Duration.ZERO);
        var original = promote(shortWindow, "expiring", "first").value();

        assertThat(shortWindow.deployments.compactIdempotency(workspace)).isOne();

        assertThatThrownBy(() -> promote(shortWindow, "expiring", "first"))
                .isInstanceOfSatisfying(LifecycleConflictException.class, conflict -> {
                    assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_RESULT_EXPIRED");
                    assertThat(conflict.resultId()).isEqualTo(original.decisionId());
                });
        assertThatThrownBy(() -> promote(shortWindow, "expiring", "changed"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(fixture.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isOne();
        assertThatThrownBy(() -> fixture.jdbc.update("delete from lifecycle_idempotency where workspace_id = ?",
                workspace.value())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("TOMBSTONE_RETAINED");
    }

    @Test
    void tombstonesRetainNoPayloadSecretOrDirectPersonalIdentifier() {
        String secret = "vault://tenant/" + PERSONAL;
        LifecycleFixture shortWindow = LifecycleFixture.postgres(LifecycleRetry.standard(), Duration.ZERO);
        shortWindow.deployments.promote(workspace, new PromotionCommand(environment.id(), "api", release.id(), null, null,
                "key-for-" + PERSONAL, PERSONAL, "requested by " + PERSONAL + " " + secret));
        assertThat(shortWindow.deployments.compactIdempotency(workspace)).isOne();

        Map<String, Object> tombstone = fixture.jdbc.queryForMap("select * from lifecycle_idempotency where workspace_id = ?",
                workspace.value());
        assertThat(tombstone.get("result_json")).isNull();
        assertThat(tombstone.get("compacted_at")).isNotNull();
        assertThat(tombstone.values().toString()).doesNotContain(PERSONAL, "key-for", "requested by");
        assertThat((String) tombstone.get("key_digest")).hasSize(64);
    }

    @Test
    void namespaceRetirementClosesAdmissionBeforeTombstonesCanBePurged() {
        promote(fixture, "retired-key", "first");
        assertThatThrownBy(() -> fixture.deployments.purgeIdempotencyNamespace(workspace, "deployment.promote"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fixture.deployments.retireIdempotencyNamespace(workspace, "deployment.promote")).isTrue();
        assertThat(fixture.deployments.purgeIdempotencyNamespace(workspace, "deployment.promote")).isOne();

        for (String key : List.of("retired-key", "brand-new-key")) {
            assertThatThrownBy(() -> promote(fixture, key, "first"))
                    .isInstanceOfSatisfying(LifecycleConflictException.class,
                            conflict -> assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_NAMESPACE_RETIRED"));
        }
        assertThatThrownBy(() -> fixture.jdbc.update("update idempotency_namespace set state = 'OPEN' where workspace_id = ?",
                workspace.value())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(fixture.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isOne();
    }

    @Test
    void replayIsDeterministicAcrossRestartAndBackupRestore() throws Exception {
        var original = promote(fixture, "durable-key", "first").value();
        LifecycleFixture restarted = LifecycleFixture.postgres();
        assertThat(promote(restarted, "durable-key", "first").value()).isEqualTo(original);

        String database = "restored_" + Long.toHexString(System.nanoTime());
        var container = LifecycleFixture.POSTGRES;
        var source = LifecycleFixture.postgresDatabase();
        assertThat(container.execInContainer("pg_dump", "-U", container.getUsername(), "-d", source.databaseName(),
                "-f", "/tmp/" + database + ".sql").getExitCode()).isZero();
        assertThat(container.execInContainer("createdb", "-U", container.getUsername(), database).getExitCode()).isZero();
        assertThat(container.execInContainer("psql", "-U", container.getUsername(), "-d", database, "-v",
                "ON_ERROR_STOP=1", "-q", "-f", "/tmp/" + database + ".sql").getExitCode()).isZero();
        var restoredSource = new DriverManagerDataSource(container.getJdbcUrl().replace("/" + container.getDatabaseName(),
                "/" + database), container.getUsername(), container.getPassword());
        LifecycleFixture restored = new LifecycleFixture(restoredSource, LifecycleRetry.standard(), Duration.ofHours(24));

        var replayed = promote(restored, "durable-key", "first");
        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.value()).isEqualTo(original);
        assertThatThrownBy(() -> promote(restored, "durable-key", "tampered"))
                .isInstanceOfSatisfying(LifecycleConflictException.class,
                        conflict -> assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(restored.count("select count(*) from deployment_revision where workspace_id = ?", workspace.value())).isOne();
        assertThatThrownBy(() -> restored.jdbc.update("update deployment_revision set placement = 'DEDICATED'"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private LifecycleModel.Idempotent<PromotionResult> promote(LifecycleFixture target, String key, String reason) {
        return target.deployments.promote(workspace, new PromotionCommand(environment.id(), "api", release.id(), null,
                null, key, "tester", reason));
    }
}
