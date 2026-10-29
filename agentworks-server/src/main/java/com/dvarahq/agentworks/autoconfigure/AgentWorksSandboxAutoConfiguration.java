package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.sandbox.ReconcilableSandboxProvider;
import com.dvarahq.agentworks.sandbox.SandboxCoordinator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

/** Wires durable sandbox coordination only when the application explicitly supplies a reconcilable provider. */
@AutoConfiguration(after = {AgentWorksLifecycleAutoConfiguration.class, AgentWorksArtifactAutoConfiguration.class},
        before = AgentWorksRunWorkerAutoConfiguration.class)
@ConditionalOnBean({AgentWorksPlanes.class, ReconcilableSandboxProvider.class})
@ConditionalOnProperty(prefix = "agentworks.sandbox.coordinator", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public final class AgentWorksSandboxAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SandboxCoordinator agentWorksSandboxCoordinator(AgentWorksPlanes planes, ReconcilableSandboxProvider provider,
            ObjectProvider<ArtifactStore> artifactStores, Environment environment) {
        return new SandboxCoordinator(planes.run().sandboxes(), provider, settings(environment),
                artifactStores.getIfUnique());
    }

    @Bean
    @ConditionalOnMissingBean
    SandboxReconciliationLifecycle agentWorksSandboxReconciliation(SandboxCoordinator coordinator,
            Environment environment) {
        Duration interval = AgentWorksRunWorkerAutoConfiguration.duration(environment,
                "agentworks.sandbox.coordinator.reconciliation-interval",
                SandboxReconciliationLifecycle.DEFAULT_INTERVAL);
        return new SandboxReconciliationLifecycle(coordinator, interval);
    }

    static SandboxCoordinator.Settings settings(Environment environment) {
        String prefix = "agentworks.sandbox.coordinator.";
        return new SandboxCoordinator.Settings(
                environment.getProperty(prefix + "provider", "docker"),
                environment.getProperty(prefix + "worker-id", defaultWorker()),
                AgentWorksRunWorkerAutoConfiguration.duration(environment, prefix + "provisioning-timeout",
                        Duration.ofMinutes(2)),
                AgentWorksRunWorkerAutoConfiguration.duration(environment, prefix + "cleanup-lease",
                        Duration.ofMinutes(1)),
                AgentWorksRunWorkerAutoConfiguration.duration(environment, prefix + "cleanup-retry",
                        Duration.ofSeconds(30)),
                environment.getProperty(prefix + "max-cleanup-claims-per-pass", Integer.class, 256));
    }

    private static String defaultWorker() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException unknown) {
            host = "sandbox-worker";
        }
        String safe = host.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.isEmpty()) safe = "sandbox-worker";
        if (safe.length() > 96) safe = safe.substring(0, 96);
        return safe + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
