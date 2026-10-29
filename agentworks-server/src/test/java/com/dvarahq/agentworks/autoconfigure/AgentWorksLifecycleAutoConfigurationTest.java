package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.deployment.DeploymentLifecycleService;
import com.dvarahq.agentworks.controlplane.deployment.EnvironmentService;
import com.dvarahq.agentworks.controlplane.lifecycle.ReleaseService;
import com.dvarahq.agentworks.controlplane.lifecycle.web.DeploymentController;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.runplane.admission.web.RunAdmissionController;
import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorksLifecycleAutoConfigurationTest {
    private final AutoConfigurations configurations = AutoConfigurations.of(AgentWorksJdbcAutoConfiguration.class,
            AgentWorksAgentDefinitionAutoConfiguration.class, AgentWorksLifecycleAutoConfiguration.class);

    private static DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private WebApplicationContextRunner web() {
        DataSource source = dataSource();
        return new WebApplicationContextRunner().withConfiguration(configurations)
                .withBean(DataSource.class, () -> source)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(new JdbcTransactionManager(source)));
    }

    @Test
    void withoutJdbcNoLifecycleServiceIsCreated() {
        new ApplicationContextRunner().withConfiguration(configurations).run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(DeploymentLifecycleService.class).doesNotHaveBean(RunAdmissionService.class));
    }

    @Test
    void webApplicationWithJdbcExposesTheLifecycleAndAdmissionApi() {
        web().run(context -> assertThat(context).hasNotFailed().hasSingleBean(ReleaseService.class)
                .hasSingleBean(EnvironmentService.class).hasSingleBean(DeploymentLifecycleService.class)
                .hasSingleBean(RunAdmissionService.class).hasSingleBean(DeploymentController.class)
                .hasSingleBean(RunAdmissionController.class));
    }

    // #175 medium 4: startup reconciliation is wired and started with the Run Plane; a failure never fails startup.
    @Test
    void runPlaneStartupReconciliationRunsAtStartupAndCanBeDisabled() {
        web().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(RunPlaneStartupReconciliation.class);
            assertThat(context).hasSingleBean(OutboxReconciliationLifecycle.class)
                    .hasSingleBean(OutboxRecoveryHealthIndicator.class);
            RunPlaneStartupReconciliation reconciliation = context.getBean(RunPlaneStartupReconciliation.class);
            assertThat(reconciliation.isRunning()).isTrue();
            // This context's database has no schema: the pass failed, startup continued, and it is being retried.
            assertThat(reconciliation.completed()).isFalse();
            assertThat(reconciliation.announced()).isEqualTo(-1);
        });
        web().withPropertyValues("agentworks.runs.startup-reconciliation.enabled=false").run(context ->
                assertThat(context).hasNotFailed().doesNotHaveBean(RunPlaneStartupReconciliation.class));
        web().withPropertyValues("agentworks.recovery.outbox.enabled=false").run(context -> assertThat(context)
                .hasNotFailed().doesNotHaveBean(OutboxReconciliationLifecycle.class)
                .doesNotHaveBean(OutboxRecoveryHealthIndicator.class));
    }

    @Test
    void applicationBeansWinAndPropertiesControlTheFeature() {
        LifecycleRetry custom = new LifecycleRetry(1);
        web().withBean(LifecycleRetry.class, () -> custom).run(context ->
                assertThat(context.getBean(LifecycleRetry.class)).isSameAs(custom));
        web().withPropertyValues("agentworks.lifecycle.enabled=false").run(context -> assertThat(context)
                .hasNotFailed().doesNotHaveBean(DeploymentLifecycleService.class).doesNotHaveBean(DeploymentController.class));
        web().withPropertyValues("agentworks.agents.enabled=false").run(context -> assertThat(context)
                .hasNotFailed().doesNotHaveBean(ReleaseService.class));
    }

    @Test
    void configuredPlaneUrlsGiveEachPlaneItsOwnPoolWhileFlywayKeepsThePrimary() {
        String control = "jdbc:h2:mem:cp-" + UUID.randomUUID();
        String run = "jdbc:h2:mem:rp-" + UUID.randomUUID();
        web().run(context -> {
            AgentWorksPlanes planes = context.getBean(AgentWorksPlanes.class);
            assertThat(planes.control().dedicated()).isFalse();
            assertThat(planes.run().dataSource()).isSameAs(context.getBean(DataSource.class));
        });
        web().withPropertyValues("agentworks.planes.control-plane.url=" + control,
                "agentworks.planes.control-plane.username=sa", "agentworks.planes.run-plane.url=" + run,
                "agentworks.planes.run-plane.username=sa").run(context -> {
            AgentWorksPlanes planes = context.getBean(AgentWorksPlanes.class);
            assertThat(context).hasNotFailed();
            assertThat(planes.control().dedicated()).isTrue();
            assertThat(planes.run().dedicated()).isTrue();
            assertThat(url(planes.control().dataSource())).isEqualTo(control);
            assertThat(url(planes.run().dataSource())).isEqualTo(run);
            assertThat(planes.control().dataSource()).isNotSameAs(context.getBean(DataSource.class));
        });
        // A partial configuration must not leave the other plane on the owner connection.
        web().withPropertyValues("agentworks.planes.control-plane.url=" + control,
                "agentworks.planes.control-plane.username=sa").run(context -> assertThat(context).hasFailed());
        web().withPropertyValues("agentworks.planes.control-plane.url=" + control, "agentworks.planes.run-plane.url=" + run,
                "agentworks.planes.run-plane.username=sa").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void idempotencyKeysBindFromVersionedBase64PropertiesAndRejectWeakKeys() {
        String strong = Base64.getEncoder().encodeToString("a-strong-idempotency-hmac-key-of-32b".getBytes(StandardCharsets.UTF_8));
        String weak = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));
        web().run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(IdempotencyDigests.class));
        web().withPropertyValues("agentworks.idempotency.active-key-version=2", "agentworks.idempotency.hmac-keys.1=" + strong,
                "agentworks.idempotency.hmac-keys.2=" + strong).run(context -> {
            IdempotencyDigests digests = context.getBean(IdempotencyDigests.class);
            assertThat(digests.activeVersion()).isEqualTo(2);
            assertThat(digests.retainedVersions()).containsExactly(0, 1, 2);
            assertThat(digests.toString()).doesNotContain(strong);
        });
        web().withPropertyValues("agentworks.idempotency.active-key-version=1", "agentworks.idempotency.hmac-keys.1=" + weak)
                .run(context -> assertThat(context).hasFailed());
        web().withPropertyValues("agentworks.idempotency.active-key-version=3", "agentworks.idempotency.hmac-keys.1=" + strong)
                .run(context -> assertThat(context).hasFailed());
    }

    private static String url(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }

    // #185 review round 3: an unstamped runtime would share one compatibility identity with every unstamped build of
    // its version. It fails startup outside local development unless the operator names the build, which is then the
    // identity; under the local-development gate it starts, with a WARN.
    @Test
    void anUnstampedRuntimeNeedsANamedBuildOutsideLocalDevelopment() {
        var codec = com.dvarahq.agentworks.runplane.execution.RunEventCodec.standard();
        var production = new org.springframework.mock.env.MockEnvironment();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        AgentWorksLifecycleAutoConfiguration.compatibility(codec, production, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("agentworks.runtime.build-id");
        assertThat(AgentWorksLifecycleAutoConfiguration.compatibility(codec, production, true))
                .isEqualTo(com.dvarahq.agentworks.runplane.execution.DurableRunEngine.compatibilityOf(codec));

        var named = new org.springframework.mock.env.MockEnvironment().withProperty("agentworks.runtime.build-id",
                "release-42");
        assertThat(AgentWorksLifecycleAutoConfiguration.compatibility(codec, named, false))
                .isEqualTo(com.dvarahq.agentworks.runplane.execution.DurableRunEngine.compatibilityOf(codec, "release-42"))
                .contains("+build.release-42-");
        // #189: a stamped build ignores the name, so a name left in place never merges stamped builds.
        assertThat(AgentWorksLifecycleAutoConfiguration.compatibility(codec, named, true))
                .isEqualTo(com.dvarahq.agentworks.runplane.execution.DurableRunEngine.compatibilityOf(codec));

        var local = new org.springframework.mock.env.MockEnvironment()
                .withProperty("agentworks.development.local", "true").withProperty("server.address", "127.0.0.1");
        assertThat(AgentWorksLifecycleAutoConfiguration.compatibility(codec, local, false))
                .isEqualTo(com.dvarahq.agentworks.runplane.execution.DurableRunEngine.compatibilityOf(codec));
    }

    @Test
    void runtimeContinuityRequiresConfiguredAuthoritiesAndASeparatedRunPlaneMustSupplyItsOwnSource() {
        Map<String, Object> pinned = Map.of("executor", Map.of("type", "spring-ai"),
                "sandbox", Map.of("provider", "docker"));
        var executor = (com.dvarahq.agentworks.agent.AgentExecutor) () -> "spring-ai";
        var docker = new DockerTestSandboxProvider();

        assertThat(AgentWorksLifecycleAutoConfiguration.runtimeAvailability(List.of(executor), List.of(docker), pinned))
                .contains(pinned);
        assertThat(AgentWorksLifecycleAutoConfiguration.runtimeAvailability(
                List.of((com.dvarahq.agentworks.agent.AgentExecutor) () -> "remote-a2a"), List.of(docker), pinned))
                .isEmpty();
        assertThat(AgentWorksLifecycleAutoConfiguration.runtimeAvailability(List.of(executor),
                List.of(new LocalTestSandboxProvider()), pinned)).isEmpty();
        assertThat(AgentWorksLifecycleAutoConfiguration.runtimeAvailability(List.of(), List.of(), pinned))
                .isEmpty();
        assertThat(AgentWorksLifecycleAutoConfiguration.runtimeAvailability(List.of(executor), List.of(), pinned))
                .isEmpty();
    }

    private static class DockerTestSandboxProvider implements com.dvarahq.agentworks.sandbox.SandboxProvider {
        @Override public Set<com.dvarahq.agentworks.domain.sandbox.SandboxCapability> capabilities() { return Set.of(); }
        @Override public com.dvarahq.agentworks.sandbox.SandboxInstance provision(
                com.dvarahq.agentworks.domain.sandbox.SandboxOwner owner) { throw new UnsupportedOperationException(); }
        @Override public com.dvarahq.agentworks.sandbox.SandboxInstance restore(
                com.dvarahq.agentworks.domain.sandbox.SandboxSnapshot snapshot) { throw new UnsupportedOperationException(); }
        @Override public void close() {}
    }

    private static final class LocalTestSandboxProvider extends DockerTestSandboxProvider {}
}
