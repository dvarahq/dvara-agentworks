package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.domain.sandbox.SandboxConfiguration;
import com.dvarahq.agentworks.sandbox.ReconcilableSandboxProvider;
import com.dvarahq.agentworks.sandbox.kubernetes.KubernetesSandboxProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Opt-in basic Kubernetes sandbox adapter; disabled unless an operator supplies an exact digest-pinned image. */
@AutoConfiguration(before = AgentWorksSandboxAutoConfiguration.class)
@ConditionalOnProperty(prefix = "agentworks.sandbox.kubernetes", name = "enabled", havingValue = "true")
public final class AgentWorksKubernetesSandboxAutoConfiguration {
    private static final String PREFIX = "agentworks.sandbox.kubernetes.";

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ReconcilableSandboxProvider.class)
    ReconcilableSandboxProvider agentWorksKubernetesSandboxProvider(Environment environment) {
        String image = required(environment, "image");
        SandboxConfiguration configuration = new SandboxConfiguration("kubernetes", "demand", Optional.of(image),
                new BigDecimal(environment.getProperty(PREFIX + "cpu", "0.5")),
                environment.getProperty(PREFIX + "memory", "256Mi"),
                // The shared value object requires a positive PID field, but Kubernetes has no portable per-Pod
                // PID-limit contract. The provider ignores this placeholder and does not advertise PID_LIMIT.
                1, "NONE",
                environment.getProperty(PREFIX + "max-runtime", "15m"));
        return new KubernetesSandboxProvider(
                environment.getProperty(PREFIX + "namespace", "agentworks-sandboxes"),
                environment.getProperty(PREFIX + "service-account", "agentworks-sandbox"),
                configuration, workspacePolicy(environment.getProperty(PREFIX + "allowed-workspaces", "")));
    }

    static Predicate<UUID> workspacePolicy(String configured) {
        if (configured == null || configured.isBlank()) return ignored -> true;
        Set<UUID> allowed;
        try {
            allowed = Arrays.stream(configured.split(",", -1)).map(String::strip).map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException malformed) {
            throw new IllegalStateException(PREFIX + "allowed-workspaces must contain comma-separated UUIDs",
                    malformed);
        }
        return allowed::contains;
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(PREFIX + name);
        if (value == null || value.isBlank()) throw new IllegalStateException(PREFIX + name + " is required");
        return value.strip();
    }
}
