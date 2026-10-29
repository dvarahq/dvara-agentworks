package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.agent.AgentExecutor;
import com.dvarahq.agentworks.domain.run.ClaimScope;
import com.dvarahq.agentworks.domain.run.DurableRunStore;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunTransaction;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ExecutorRouter;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import com.dvarahq.agentworks.runplane.execution.RunEventCodec;
import com.dvarahq.agentworks.runplane.execution.RunHandler;
import com.dvarahq.agentworks.runplane.execution.RunOutcome;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Issue #34: the Run Plane worker pool is opt-in, configurable, and routes runs by their pinned executor type. */
class AgentWorksRunWorkerAutoConfigurationTest {
    private final AtomicInteger polls = new AtomicInteger();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentWorksRunWorkerAutoConfiguration.class))
            .withBean(DurableRunEngine.class, this::idleEngine);

    /** An engine over a store with nothing to claim; it counts the polls. */
    private DurableRunEngine idleEngine() {
        return new DurableRunEngine(new DurableRunStore() {
            @Override
            public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
                polls.incrementAndGet();
                return Optional.empty();
            }

            @Override
            public boolean keepsClaimQuarantine() {
                return true;
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new LifecycleRetry(1));
    }

    static final class TestExecutor implements AgentExecutor, RunHandler {
        private final String type;

        TestExecutor(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public RunOutcome execute(RunContext context) {
            return RunOutcome.completed(null);
        }
    }

    // Review 3, item 10: durations accept Spring Boot's formats as well as ISO-8601.
    @Test
    void durationsAcceptSpringBootAndIsoFormats() {
        runner.withBean(TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withPropertyValues("agentworks.runs.worker.enabled=true", "agentworks.runs.worker.lease-duration=45s",
                        "agentworks.runs.worker.idle-backoff=PT0.5S", "agentworks.runs.worker.claim-quarantine=10m")
                .run(context -> {
                    var settings = AgentWorksRunWorkerAutoConfiguration.settings(context.getEnvironment());
                    assertThat(settings.leaseDuration()).isEqualTo(java.time.Duration.ofSeconds(45));
                    assertThat(settings.idleBackoff()).isEqualTo(java.time.Duration.ofMillis(500));
                    assertThat(settings.claimQuarantine()).isEqualTo(java.time.Duration.ofMinutes(10));
                });
        // Startup reconciliation starts before the worker pool and stops after it.
        assertThat(RunPlaneStartupReconciliation.PHASE).isLessThan(RunWorkerLifecycle.PHASE);
    }

    // Review 4, item 10: a duration without a unit fails startup with an error naming the property, instead of
    // silently meaning milliseconds.
    @Test
    void aDurationWithoutAUnitFailsStartupNamingTheProperty() {
        runner.withBean(TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withPropertyValues("agentworks.runs.worker.enabled=true", "agentworks.runs.worker.lease-duration=30")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("agentworks.runs.worker.lease-duration").hasMessageContaining("unit"));
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("agentworks.spring-ai.model-timeout", " 300 ");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AgentWorksSpringAiAutoConfiguration.settings(environment))
                .hasMessageContaining("agentworks.spring-ai.model-timeout").hasMessageContaining("unit");
        environment.setProperty("agentworks.spring-ai.model-timeout", "300ms");
        assertThat(AgentWorksSpringAiAutoConfiguration.settings(environment).modelTimeout())
                .isEqualTo(java.time.Duration.ofMillis(300));
    }

    // #181 low 5: the unit check read only the dashed property name, so a relaxed form (camelCase, or an environment
    // variable) still bound a bare number as milliseconds. It now reads the value with the same relaxed binding.
    @Test
    void aDurationWithoutAUnitIsRefusedUnderEveryRelaxedName() {
        for (String relaxed : java.util.List.of("agentworks.runs.worker.leaseDuration",
                "agentworks.runs.worker.lease_duration")) {
            var environment = new org.springframework.mock.env.MockEnvironment().withProperty(relaxed, "30");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> AgentWorksRunWorkerAutoConfiguration.settings(
                    environment)).as(relaxed).hasMessageContaining("agentworks.runs.worker.lease-duration")
                    .hasMessageContaining("unit");
        }
        var variables = new org.springframework.core.env.StandardEnvironment();
        String name = org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        variables.getPropertySources().replace(name, new org.springframework.core.env.SystemEnvironmentPropertySource(
                name, java.util.Map.of("AGENTWORKS_RUNS_WORKER_LEASEDURATION", "30")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AgentWorksRunWorkerAutoConfiguration.settings(variables))
                .hasMessageContaining("unit");
        var camel = new org.springframework.mock.env.MockEnvironment().withProperty("agentworks.runs.worker.leaseDuration",
                "45s");
        assertThat(AgentWorksRunWorkerAutoConfiguration.settings(camel).leaseDuration())
                .isEqualTo(java.time.Duration.ofSeconds(45));
    }

    // Review 3, item 4: a JVM failure in a poll is loud but never silently shrinks the pool.
    @Test
    void aPollThreadSurvivesAJvmFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DurableRunEngine failing = new DurableRunEngine(new DurableRunStore() {
            @Override
            public <T> T inTransaction(WorkspaceId workspace, Function<RunTransaction, T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Optional<T> claimNext(ClaimScope scope, BiFunction<RunRecord, RunTransaction, T> work) {
                if (calls.incrementAndGet() == 1) throw new OutOfMemoryError("simulated");
                return Optional.empty();
            }

            @Override
            public boolean keepsClaimQuarantine() {
                return true;
            }
        }, RunEventCodec.standard(), new ReleaseManifestReader(), new LifecycleRetry(1));
        var pool = new RunWorkerLifecycle(failing, context -> RunOutcome.completed(null), java.util.Set.of("spring-ai"),
                new RunWorkerLifecycle.Settings(1, "node", java.time.Duration.ofSeconds(30), java.time.Duration.ofMillis(10),
                        java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(2)));
        pool.start();
        try {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (calls.get() < 5 && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(calls.get()).isGreaterThanOrEqualTo(5);
        } finally {
            pool.stop();
        }
    }

    @Test
    void noWorkerStartsUnlessEnabled() throws Exception {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(RunWorkerLifecycle.class)
                    .doesNotHaveBean(ExecutorRouter.class);
            Thread.sleep(100);
            assertThat(polls).hasValue(0);
        });
    }

    @Test
    void anEnabledPoolPollsWithItsConfiguredWorkersAndRoutesByExecutorType() {
        runner.withBean(TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withPropertyValues("agentworks.runs.worker.enabled=true", "agentworks.runs.worker.threads=3",
                        "agentworks.runs.worker.idle-backoff=PT0.01S", "agentworks.runs.worker.lease-duration=PT15S",
                        "agentworks.runs.worker.id-prefix=node-a")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RunWorkerLifecycle pool = context.getBean(RunWorkerLifecycle.class);
                    assertThat(pool.isRunning()).isTrue();
                    assertThat(pool.workers()).isEqualTo(3);
                    assertThat(context.getBean(ExecutorRouter.class).types()).containsExactly("spring-ai");
                    long deadline = System.nanoTime() + 5_000_000_000L;
                    while (polls.get() < 6 && System.nanoTime() < deadline) Thread.sleep(10);
                    assertThat(polls.get()).isGreaterThanOrEqualTo(6);
                    assertThat(AgentWorksRunWorkerAutoConfiguration.settings(context.getEnvironment()).workerIdPrefix())
                            .isEqualTo("node-a");
                    pool.stop();
                    assertThat(pool.isRunning()).isFalse();
                    int stopped = polls.get();
                    Thread.sleep(100);
                    assertThat(polls.get()).isLessThanOrEqualTo(stopped + 3);
                });
    }

    @Test
    void theShippedRouterResolvesCapabilitiesBeforeCallingAnExecutor() {
        var capabilities = org.mockito.Mockito.mock(
                com.dvarahq.agentworks.runplane.execution.RunCapabilityResolver.class);
        RunContext run = org.mockito.Mockito.mock(RunContext.class);
        com.dvarahq.agentworks.domain.run.RunPins pins = org.mockito.Mockito.mock(
                com.dvarahq.agentworks.domain.run.RunPins.class);
        org.mockito.Mockito.when(pins.workspaceId()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(pins.releaseId()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(pins.deploymentId()).thenReturn(java.util.UUID.randomUUID());
        org.mockito.Mockito.when(pins.revisionId()).thenReturn(java.util.UUID.randomUUID());
        ReleaseManifestReader.RunManifest manifest = org.mockito.Mockito.mock(ReleaseManifestReader.RunManifest.class);
        org.mockito.Mockito.when(manifest.manifestVersion()).thenReturn(12);
        org.mockito.Mockito.when(manifest.executorType()).thenReturn("spring-ai");
        org.mockito.Mockito.when(run.pins()).thenReturn(pins);
        org.mockito.Mockito.when(run.manifest()).thenReturn(manifest);
        org.mockito.Mockito.when(capabilities.resolve(org.mockito.ArgumentMatchers.any(RunContext.class)))
                .thenReturn(Optional.empty());
        runner.withBean(TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withBean(com.dvarahq.agentworks.runplane.execution.RunCapabilityResolver.class, () -> capabilities)
                .withPropertyValues("agentworks.runs.worker.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    try {
                        context.getBean(ExecutorRouter.class).execute(run);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                    org.mockito.Mockito.verify(capabilities).resolve(org.mockito.ArgumentMatchers.argThat(resolved ->
                            resolved.pins() == pins && resolved.compiledDefinition().isPresent()));
                });
    }

    @Test
    void twoExecutorsForOneTypeOrAnInvalidLeaseOrQuarantineFailStartup() {
        runner.withBean("first", TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withBean("second", TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withPropertyValues("agentworks.runs.worker.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("two AgentExecutor beans handle executor type 'spring-ai'"));
        runner.withBean(TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withPropertyValues("agentworks.runs.worker.enabled=true", "agentworks.runs.worker.lease-duration=0s")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("lease"));
        // #181 medium 3: a zero quarantine would disable the exclusion entirely, so it fails startup.
        runner.withBean(TestExecutor.class, () -> new TestExecutor("spring-ai"))
                .withPropertyValues("agentworks.runs.worker.enabled=true", "agentworks.runs.worker.claim-quarantine=0s")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("claimQuarantine must be positive"));
        // Review 3, item 5: an enabled worker with nothing to run fails startup rather than claiming and pausing runs.
        runner.withPropertyValues("agentworks.runs.worker.enabled=true")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("no executor"));
    }
}
