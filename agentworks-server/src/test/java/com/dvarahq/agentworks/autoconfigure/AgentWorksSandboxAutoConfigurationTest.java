package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.sandbox.ReconcilableSandboxProvider;
import com.dvarahq.agentworks.sandbox.SandboxCoordinator;
import com.dvarahq.agentworks.storage.sandbox.JdbcSandboxLifecycleAuthority;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The coordinator is opt-in through a provider bean and converges before run workers start. */
class AgentWorksSandboxAutoConfigurationTest {
    private final JdbcSandboxLifecycleAuthority authority = mock(JdbcSandboxLifecycleAuthority.class);
    private final ReconcilableSandboxProvider provider = mock(ReconcilableSandboxProvider.class);
    private final AgentWorksPlanes planes = mock(AgentWorksPlanes.class);

    @Test
    void aReconcilableProviderWiresTheCoordinatorAndLifecycle() {
        AgentWorksPlanes.Plane run = mock(AgentWorksPlanes.Plane.class);
        when(planes.run()).thenReturn(run);
        when(run.sandboxes()).thenReturn(authority);
        when(authority.workspacesWithOpenResources("docker")).thenReturn(List.of());
        when(provider.discoverOwnedSandboxes()).thenReturn(Set.of());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentWorksSandboxAutoConfiguration.class))
                .withBean(AgentWorksPlanes.class, () -> planes)
                .withBean(ReconcilableSandboxProvider.class, () -> provider)
                .withPropertyValues("agentworks.sandbox.coordinator.reconciliation-interval=1h")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SandboxCoordinator.class)
                            .hasSingleBean(SandboxReconciliationLifecycle.class);
                    assertThat(context.getBean(SandboxReconciliationLifecycle.class).getPhase())
                            .isGreaterThan(RunPlaneStartupReconciliation.PHASE)
                            .isLessThan(RunWorkerLifecycle.PHASE);
                });
    }

    @Test
    void noProviderMeansNoCoordinatorOrBackgroundLifecycle() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentWorksSandboxAutoConfiguration.class))
                .withBean(AgentWorksPlanes.class, () -> planes)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SandboxCoordinator.class)
                        .doesNotHaveBean(SandboxReconciliationLifecycle.class));
    }
}
