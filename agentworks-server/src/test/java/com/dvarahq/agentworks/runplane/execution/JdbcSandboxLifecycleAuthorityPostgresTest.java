package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource.State;
import com.dvarahq.agentworks.storage.sandbox.JdbcSandboxLifecycleAuthority;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dvarahq.agentworks.runplane.execution.RunScenario.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL is the authority for sandbox ownership, lifecycle, cleanup leases, and cohort work accounting. */
class JdbcSandboxLifecycleAuthorityPostgresTest {
    private final RunScenario scenario = new RunScenario();
    private final JdbcSandboxLifecycleAuthority sandboxes = new JdbcSandboxLifecycleAuthority(scenario.fixture.jdbc,
            scenario.fixture.transactions);

    @Test
    void reservationActivationAndCleanupAreAtomicWithTheCohortLedger() {
        RunRecord run = scenario.submit(Map.of("task", "sandbox"));
        UUID id = UUID.randomUUID();

        SandboxResource reserved = sandboxes.reserve(scenario.workspace, id, owner(run), "docker",
                Duration.ofHours(1), Duration.ofMinutes(2));
        assertThat(reserved.state()).isEqualTo(State.PROVISIONING);
        assertLedger(id, "PENDING");

        SandboxResource active = sandboxes.activate(scenario.workspace, id);
        assertThat(active.state()).isEqualTo(State.ACTIVE);
        assertLedger(id, "ACTIVE");

        assertThat(sandboxes.requestCleanup(scenario.workspace, id).state()).isEqualTo(State.CLEANUP_PENDING);
        SandboxResource claimed = sandboxes.claimCleanup(scenario.workspace, "docker", "cleaner", Duration.ofMinutes(1))
                .orElseThrow();
        assertThat(claimed.state()).isEqualTo(State.CLEANING);
        assertThat(claimed.cleanupFence()).isOne();

        SandboxResource terminal = sandboxes.cleanupSucceeded(scenario.workspace, id, "cleaner", 1);
        assertThat(terminal.state()).isEqualTo(State.TERMINAL);
        assertThat(terminal.terminalAt()).isPresent();
        assertLedger(id, "TERMINAL");
        assertThat(sandboxes.open(scenario.workspace)).isEmpty();
    }

    @Test
    void competingCleanupWorkersProduceExactlyOneFencedOwner() throws Exception {
        RunRecord run = scenario.submit(Map.of());
        UUID id = reserveForCleanup(run);

        List<Object> outcomes = race(12, index -> sandboxes.claimCleanup(scenario.workspace, "docker", "worker-" + index,
                Duration.ofMinutes(1)));

        List<SandboxResource> winners = outcomes.stream().filter(Optional.class::isInstance)
                .map(Optional.class::cast).flatMap(Optional::stream).map(SandboxResource.class::cast).toList();
        assertThat(winners).singleElement().satisfies(winner -> {
            assertThat(winner.reference().value()).isEqualTo(id);
            assertThat(winner.cleanupFence()).isOne();
            assertThat(winner.cleanupLease()).isPresent();
        });
        assertThat(scenario.count("select count(*) from sandbox_resource where id = ? and state = 'CLEANING'", id))
                .isOne();
    }

    @Test
    void expiredCleanupLeaseIsRecoveredWithANewFenceAndRejectsTheStaleWorker() {
        RunRecord run = scenario.submit(Map.of());
        UUID id = reserveForCleanup(run);
        SandboxResource first = sandboxes.claimCleanup(scenario.workspace, "docker", "worker-a", Duration.ofMinutes(1))
                .orElseThrow();
        scenario.fixture.bypassFence("update sandbox_resource set cleanup_until = clock_timestamp() - "
                + "interval '1 millisecond' where id = ?", id);

        SandboxResource second = sandboxes.claimCleanup(scenario.workspace, "docker", "worker-b", Duration.ofMinutes(1))
                .orElseThrow();

        assertThat(second.cleanupFence()).isEqualTo(first.cleanupFence() + 1);
        assertThat(second.cleanupLease().orElseThrow().owner()).isEqualTo("worker-b");
        assertThatThrownBy(() -> sandboxes.cleanupSucceeded(scenario.workspace, id, "worker-a", first.cleanupFence()))
                .hasMessageContaining("stale sandbox cleanup lease");
        assertThat(sandboxes.cleanupSucceeded(scenario.workspace, id, "worker-b", second.cleanupFence()).state())
                .isEqualTo(State.TERMINAL);
    }

    @Test
    void failedCleanupRetriesAndDatabaseFencesImmutableOrUnadmittedOwnership() {
        RunRecord run = scenario.submit(Map.of());
        UUID id = reserveForCleanup(run);
        SandboxResource first = sandboxes.claimCleanup(scenario.workspace, "docker", "worker", Duration.ofMinutes(1))
                .orElseThrow();
        SandboxResource failed = sandboxes.cleanupFailed(scenario.workspace, id, "worker", first.cleanupFence(),
                "PROVIDER_UNAVAILABLE", Duration.ofSeconds(30));
        assertThat(failed.state()).isEqualTo(State.CLEANUP_PENDING);
        assertThat(failed.cleanupFailures()).isOne();
        assertThat(failed.lastFailureCode()).contains("PROVIDER_UNAVAILABLE");
        assertThat(sandboxes.claimCleanup(scenario.workspace, "docker", "early-worker", Duration.ofMinutes(1))).isEmpty();

        scenario.fixture.bypassFence("update sandbox_resource set cleanup_after = clock_timestamp() - "
                + "interval '1 millisecond' where id = ?", id);
        SandboxResource retry = sandboxes.claimCleanup(scenario.workspace, "docker", "retry-worker", Duration.ofMinutes(1))
                .orElseThrow();
        assertThat(retry.cleanupFence()).isEqualTo(first.cleanupFence() + 1);

        assertThatThrownBy(() -> scenario.fixture.jdbc.update(
                "update sandbox_resource set run_id = ? where id = ?", UUID.randomUUID(), id))
                .hasStackTraceContaining("IMMUTABLE_IDENTITY");
        sandboxes.cleanupSucceeded(scenario.workspace, id, "retry-worker", retry.cleanupFence());
        assertThatThrownBy(() -> scenario.fixture.asRole("agentworks_run_plane",
                "update sandbox_resource set state = 'ACTIVE', terminal_at = null where id = ?", id))
                .hasStackTraceContaining("TERMINAL_SANDBOX");

        long version = scenario.fixture.deployments.cohort(scenario.workspace, scenario.promotion.cohortId())
                .transitionVersion();
        var suspended = scenario.fixture.deployments.suspend(scenario.workspace,
                new com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand(
                        scenario.promotion.cohortId(), version, null, "operator", "incident")).value();
        assertThat(suspended.state()).isEqualTo("SUSPENDED");
        assertThatThrownBy(() -> sandboxes.reserve(scenario.workspace, UUID.randomUUID(), owner(run), "docker",
                Duration.ofHours(1), Duration.ofMinutes(2))).hasStackTraceContaining("COHORT_NOT_ADMITTING SUSPENDED");
    }

    private UUID reserveForCleanup(RunRecord run) {
        UUID id = UUID.randomUUID();
        sandboxes.reserve(scenario.workspace, id, owner(run), "docker", Duration.ofHours(1), Duration.ofMinutes(2));
        sandboxes.activate(scenario.workspace, id);
        sandboxes.requestCleanup(scenario.workspace, id);
        return id;
    }

    private SandboxOwner owner(RunRecord run) {
        UUID organization = scenario.fixture.jdbc.queryForObject(
                "select organization_id from workspace where id = ?", UUID.class, scenario.workspace.value());
        return new SandboxOwner(organization, scenario.workspace.value(), run.id(), run.pins().releaseId(),
                run.pins().revisionId(), run.pins().cohortId());
    }

    private void assertLedger(UUID sandbox, String state) {
        assertThat(scenario.count("select count(*) from cohort_work_item where workspace_id = ? and category = "
                + "'SANDBOX' and subject_id = ? and state = ?", scenario.workspace.value(), sandbox, state)).isOne();
    }
}
