package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.sandbox.ReconcilableSandboxProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorksKubernetesSandboxAutoConfigurationTest {
    private static final String IMAGE = "registry.example/sandbox@sha256:"
            + "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    @Test
    void disabledByDefaultAndRequiresDigestPinnedImageWhenEnabled() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentWorksKubernetesSandboxAutoConfiguration.class));
        runner.run(context -> assertThat(context).doesNotHaveBean(ReconcilableSandboxProvider.class));
        runner.withPropertyValues("agentworks.sandbox.kubernetes.enabled=true")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("agentworks.sandbox.kubernetes.image is required"));
    }

    @Test
    void optInCreatesProviderAndValidatesWorkspaceAllowlist() {
        UUID workspace = UUID.randomUUID();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentWorksKubernetesSandboxAutoConfiguration.class))
                .withPropertyValues(
                        "agentworks.sandbox.kubernetes.enabled=true",
                        "agentworks.sandbox.kubernetes.image=" + IMAGE,
                        "agentworks.sandbox.kubernetes.namespace=sandboxes",
                        "agentworks.sandbox.kubernetes.service-account=runner",
                        "agentworks.sandbox.kubernetes.allowed-workspaces=" + workspace)
                .run(context -> assertThat(context).hasNotFailed()
                        .hasSingleBean(ReconcilableSandboxProvider.class));

        assertThat(AgentWorksKubernetesSandboxAutoConfiguration.workspacePolicy(workspace.toString()).test(workspace))
                .isTrue();
        assertThat(AgentWorksKubernetesSandboxAutoConfiguration.workspacePolicy(workspace.toString())
                .test(UUID.randomUUID())).isFalse();
    }
}
