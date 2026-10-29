package com.dvarahq.agentworks.runplane.reconciliation;

import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher;
import com.dvarahq.agentworks.controlplane.deployment.ControlPlaneOutboxDispatcher.Notification;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleFixture;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.EnvironmentView;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.PromotionResult;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Canonical desired-state convergence (ADR-008), replacing the Phase 0 spike-004 evidence: promotions commit with
 * their outbox events, delivery is at-least-once and tracked per row, and the Run Plane converges on authoritative
 * deployment state however notifications are lost, duplicated, reordered, delayed, or committed out of order.
 */
class OutboxReconciliationPostgresTest {
    private final LifecycleFixture fixture = LifecycleFixture.postgres();
    private final ControlPlaneOutboxDispatcher dispatcher = new ControlPlaneOutboxDispatcher(fixture.jdbc, fixture.transactions);
    private final WorkspaceId workspace = fixture.workspace();
    private final EnvironmentView environment = fixture.environment(workspace, "prod");
    private final String consumer = "run-plane-" + UUID.randomUUID().toString().substring(0, 8);

    private PromotionResult promote(String version) {
        return promote("api", version);
    }

    private PromotionResult promote(String deployment, String version) {
        return fixture.promote(workspace, environment, deployment, fixture.release(workspace, "agent", version));
    }

    /** Drains every pending event for {@code name}, keeping this workspace's notifications. */
    private List<Notification> drain(ControlPlaneOutboxDispatcher source, String name, Consumer<Notification> sink) {
        List<Notification> mine = new ArrayList<>();
        while (source.dispatch(name, 1000, notification -> {
            sink.accept(notification);
            if (workspace.value().equals(notification.workspaceId())) mine.add(notification);
        }) > 0) {
            // keep draining
        }
        return mine;
    }

    // #201 review, low 6: on PostgreSQL a dispatch inside a caller's transaction, at any isolation level, runs in a
    // transaction of its own: its snapshot never predates its lock, its lock ends with its own commit, and the caller's
    // rollback does not undo its delivery records.
    @Test
    void aDispatchInsideACallersTransactionRunsInItsOwnOnPostgres() {
        promote("1.0.0");
        var repeatable = new org.springframework.transaction.support.TransactionTemplate(
                fixture.transactions.getTransactionManager());
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        List<Notification> delivered = new ArrayList<>();
        assertThatThrownBy(() -> repeatable.executeWithoutResult(status -> {
            drain(dispatcher, consumer, delivered::add);
            throw new IllegalStateException("the caller fails");
        })).hasMessageContaining("the caller fails");
        assertThat(delivered).anyMatch(n -> workspace.value().equals(n.workspaceId()));

        assertThat(drain(dispatcher, consumer, n -> { })).isEmpty();
    }

    // #175 low: two dispatchers of one consumer delivered the same batch. The second now waits for the first to commit
    // its delivery records, then finds nothing left to deliver.
    @Test
    void concurrentDispatchersOfOneConsumerNeverDeliverTheSameEvent() throws Exception {
        promote("1.0.0");
        var second = new ControlPlaneOutboxDispatcher(fixture.jdbc, fixture.transactions);
        var inside = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        List<UUID> firstDelivered = java.util.Collections.synchronizedList(new ArrayList<>());
        List<UUID> secondDelivered = java.util.Collections.synchronizedList(new ArrayList<>());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> dispatcher.dispatch(consumer, 1000, notification -> {
                firstDelivered.add(notification.eventId());
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(inside.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var competing = pool.submit(() -> second.dispatch(consumer, 1000,
                    notification -> secondDelivered.add(notification.eventId())));
            // Proceed once the second dispatcher either finished or waits on the first one's advisory lock.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (!competing.isDone() && fixture.count("select count(*) from pg_stat_activity where "
                    + "wait_event_type = 'Lock' and wait_event = 'advisory'") == 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            release.countDown();
            assertThat(first.get(30, java.util.concurrent.TimeUnit.SECONDS)).isPositive();
            competing.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(secondDelivered).doesNotContainAnyElementsOf(firstDelivered);
        assertThat(dispatcher.pending(consumer)).isZero();
    }

    @Test
    void deliveryResumesAfterADispatcherCrashWithoutLosingOrRecordingTheBatch() {
        PromotionResult first = promote("1.0.0");
        drain(dispatcher, consumer, notification -> { });
        promote("2.0.0");
        long pending = dispatcher.pending(consumer);
        assertThat(pending).isPositive();

        // The dispatcher crashes after handing notifications over but before recording their delivery.
        assertThatThrownBy(() -> dispatcher.dispatch(consumer, 1000, notification -> {
            throw new IllegalStateException("dispatcher crashed after send");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(dispatcher.pending(consumer)).isEqualTo(pending);

        var restarted = new ControlPlaneOutboxDispatcher(fixture.jdbc, fixture.transactions);
        List<Notification> redelivered = drain(restarted, consumer, notification -> { });
        assertThat(redelivered).extracting(Notification::eventType).contains("DESIRED_DEPLOYMENT_CHANGED");
        assertThat(redelivered).allSatisfy(notification ->
                assertThat(notification.deploymentId()).isEqualTo(first.deploymentId()));
        assertThat(dispatcher.pending(consumer)).isZero();
        // Every event also stays in the append-only outbox for audit.
        assertThat(fixture.count("select count(*) from control_plane_outbox where workspace_id = ?", workspace.value()))
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void anEventWhoseLowerSequenceCommitsAfterAHigherOneIsStillDelivered() throws Exception {
        PromotionResult first = promote("1.0.0");
        drain(dispatcher, consumer, notification -> { });
        UUID late = UUID.randomUUID();
        try (Connection slow = fixture.dataSource.getConnection()) {
            slow.setAutoCommit(false);
            // A slow control-plane transaction allocates its outbox sequence first...
            try (PreparedStatement insert = slow.prepareStatement("insert into control_plane_outbox(workspace_id, id, "
                    + "event_type, decision_id, payload_json, created_at) values (?, ?, 'DESIRED_DEPLOYMENT_CHANGED', ?, ?, "
                    + "current_timestamp)")) {
                insert.setObject(1, workspace.value());
                insert.setObject(2, late);
                insert.setObject(3, first.decisionId());
                insert.setString(4, "{\"deploymentId\":\"" + first.deploymentId() + "\"}");
                insert.executeUpdate();
            }
            // ...while a later promotion commits a higher sequence, which is delivered.
            promote("2.0.0");
            List<Notification> before = drain(dispatcher, consumer, notification -> { });
            assertThat(before).extracting(Notification::eventId).doesNotContain(late).isNotEmpty();
            long lateSequence = fixture.count("select max(sequence_number) from control_plane_outbox where workspace_id = ?",
                    workspace.value());
            assertThat(lateSequence).isGreaterThan(0);
            slow.commit();
        }
        List<Notification> after = drain(dispatcher, consumer, notification -> { });

        assertThat(after).extracting(Notification::eventId).containsExactly(late);
        assertThat(after.getFirst().sequence()).isLessThan(fixture.count("select max(sequence_number) "
                + "from control_plane_outbox where workspace_id = ?", workspace.value()));
        assertThat(dispatcher.pending(consumer)).isZero();
    }

    @Test
    void duplicateStaleAndReorderedNotificationsNeverRegressTheObservation() {
        var reconciler = new DeploymentReconciler(fixture.tenants, fixture.lifecycle);
        promote("1.0.0");
        List<Notification> first = drain(dispatcher, consumer, notification -> { });
        PromotionResult latest = promote("2.0.0");
        List<Notification> second = drain(dispatcher, consumer, notification -> { });

        for (Notification notification : second) reconciler.reconcile(workspace, notification.deploymentId());
        var observed = reconciler.observed(workspace, latest.deploymentId()).orElseThrow();
        // Duplicates and the older notification re-read the same authoritative row and change nothing.
        for (Notification stale : first) assertThat(reconciler.reconcile(workspace, stale.deploymentId())).isFalse();
        for (Notification duplicate : second) assertThat(reconciler.reconcile(workspace, duplicate.deploymentId())).isFalse();

        assertThat(reconciler.observed(workspace, latest.deploymentId())).contains(observed);
        assertThat(observed.currentRevisionId()).isEqualTo(latest.revisionId());
        assertThat(observed.currentRoutingGenerationId()).isEqualTo(latest.routingGenerationId());
    }

    @Test
    void aRestartedRunPlaneObservesExistingDeploymentsWhoseEventsItAlreadyReceived() {
        PromotionResult first = promote("1.0.0");
        promote("2.0.0");
        PromotionResult latest = promote("3.0.0");
        var reconciler = new DeploymentReconciler(fixture.tenants, fixture.lifecycle);
        drain(dispatcher, consumer, notification -> {
            if (workspace.value().equals(notification.workspaceId())) {
                reconciler.reconcile(workspace, notification.deploymentId());
            }
        });
        assertThat(dispatcher.pending(consumer)).isZero();

        // After a restart the node has no observations, and nothing is redelivered to its consumer...
        var restarted = new DeploymentReconciler(fixture.tenants, fixture.lifecycle);
        assertThat(drain(dispatcher, consumer, notification -> { })).isEmpty();
        assertThat(restarted.observed(workspace, first.deploymentId())).isEmpty();
        // ...so startup reconciliation re-reads every deployment from authoritative state.
        int announced = dispatcher.announceDeployments(notification ->
                restarted.reconcile(new WorkspaceId(notification.workspaceId()), notification.deploymentId()));

        assertThat(announced).isPositive();
        assertThat(restarted.observed(workspace, first.deploymentId()).orElseThrow().currentRevisionId())
                .isEqualTo(latest.revisionId());
        assertThat(fixture.count("select count(*) from control_plane_outbox where workspace_id = ? "
                + "and event_type = 'DESIRED_DEPLOYMENT_CHANGED'", workspace.value())).isEqualTo(3);
        // Another workspace cannot observe this deployment.
        assertThat(restarted.reconcile(fixture.workspace(), latest.deploymentId())).isFalse();
    }

    // #175 medium 4: the production startup component re-observes deployments a restarted node already received.
    @Test
    void theStartupComponentReobservesDeploymentsAfterARestart() throws Exception {
        PromotionResult first = promote("1.0.0");
        PromotionResult latest = promote("2.0.0");
        drain(dispatcher, consumer, notification -> { });
        var restarted = new DeploymentReconciler(fixture.tenants, fixture.lifecycle);
        var startup = new com.dvarahq.agentworks.autoconfigure.RunPlaneStartupReconciliation(dispatcher, restarted);

        startup.start();
        startup.start();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (!startup.completed() && System.nanoTime() < deadline) Thread.sleep(10);
        startup.stop();

        assertThat(startup.completed()).isTrue();
        assertThat(startup.passes()).isOne();
        assertThat(startup.announced()).isPositive();
        assertThat(restarted.observed(workspace, first.deploymentId()).orElseThrow().currentRevisionId())
                .isEqualTo(latest.revisionId());
    }

    @Test
    void anOfflineRunPlaneConvergesAfterLostNotificationsWhileEveryEventStaysAuditable() {
        PromotionResult first = promote("1.0.0");
        var reconciler = new DeploymentReconciler(fixture.tenants, fixture.lifecycle);
        reconciler.reconcile(workspace, first.deploymentId());
        promote("2.0.0");
        PromotionResult latest = promote("3.0.0");
        // Every notification for the later promotions is lost; reconciling what the node observes converges anyway.
        drain(dispatcher, consumer, notification -> { });

        assertThat(reconciler.reconcileObserved()).isOne();
        assertThat(reconciler.observed(workspace, first.deploymentId()).orElseThrow().currentRevisionId())
                .isEqualTo(latest.revisionId());
    }

    @Test
    void poisonWorkBacksOffQuarantinesAndNeverBlocksHealthyRows() throws Exception {
        PromotionResult poison = promote("poison", "1.0.0");
        promote("healthy", "2.0.0");
        var policy = new ControlPlaneOutboxDispatcher.RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(1), 2);
        String secret = "customer-prompt-must-not-be-stored";

        var first = dispatcher.dispatchRecovering(consumer, 1000, policy, notification -> {
            if (poison.deploymentId().equals(notification.deploymentId())) {
                throw new IllegalStateException(secret);
            }
        });
        assertThat(first.failed()).isPositive();
        assertThat(first.delivered()).isPositive();
        assertThat(first.quarantined()).isZero();

        Thread.sleep(5);
        var second = dispatcher.dispatchRecovering(consumer, 1000, policy, notification -> {
            if (poison.deploymentId().equals(notification.deploymentId())) {
                throw new IllegalStateException(secret);
            }
        });
        assertThat(second.quarantined()).isPositive();
        List<ControlPlaneOutboxDispatcher.Quarantined> quarantined = dispatcher.quarantined(workspace, consumer);
        assertThat(quarantined).isNotEmpty().allSatisfy(record -> {
            assertThat(record.attempts()).isEqualTo(2);
            assertThat(record.lastErrorCode()).isEqualTo(IllegalStateException.class.getName());
            assertThat(record.lastErrorDigest()).startsWith("sha256:").doesNotContain(secret);
        });
        assertThat(fixture.count("select count(*) from control_plane_outbox_attempt where last_error_digest like ?",
                "%" + secret + "%")).isZero();

        UUID released = quarantined.getFirst().outboxId();
        assertThat(dispatcher.release(workspace, consumer, released)).isTrue();
        assertThat(dispatcher.release(fixture.workspace(), consumer, released)).isFalse();
        assertThat(dispatcher.dispatchRecovering(consumer, 1000, policy, notification -> { }).delivered()).isPositive();
        assertThat(dispatcher.quarantined(workspace, consumer)).noneMatch(row -> row.outboxId().equals(released));
    }

    @Test
    void quarantinedRowsRemainVisibleInBacklogAndLagDiagnostics() {
        promote("1.0.0");
        var immediate = new ControlPlaneOutboxDispatcher.RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(1), 1);
        dispatcher.dispatchRecovering(consumer, 1000, immediate, notification -> {
            throw new IllegalStateException("poison");
        });

        assertThat(dispatcher.quarantined(consumer)).isPositive();
        assertThat(dispatcher.pending(consumer)).isPositive();
        assertThat(dispatcher.oldestPendingAgeSeconds(consumer)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void theRunPlaneRoleCannotForgeDesiredStateTheOutboxOrDeliveryRecords() {
        PromotionResult promoted = promote("1.0.0");
        drain(dispatcher, consumer, notification -> { });
        for (String forged : List.of(
                "update agent_deployment set revision = revision + 1 where id = ?",
                "delete from control_plane_outbox where decision_id is not null and ? is not null",
                "delete from control_plane_outbox_delivery where ? is not null",
                "insert into control_plane_outbox_delivery(consumer, workspace_id, outbox_id, delivered_at) "
                        + "select 'forged', workspace_id, id, current_timestamp from control_plane_outbox "
                        + "where ? is not null")) {
            assertThatThrownBy(() -> fixture.asRole("agentworks_run_plane", forged, promoted.deploymentId())).as(forged)
                    .hasStackTraceContaining("permission denied");
        }
        assertThat(dispatcher.pending(consumer)).isZero();
    }
}
