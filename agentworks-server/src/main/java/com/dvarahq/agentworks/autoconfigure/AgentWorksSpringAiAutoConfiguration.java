package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.agent.springai.SpringAiAgentExecutor;
import com.dvarahq.agentworks.agent.springai.GatewayReplayModelExecutor;
import com.dvarahq.agentworks.replay.ReplayModelExecutor;
import com.dvarahq.agentworks.context.BasicContextCompiler;
import com.dvarahq.agentworks.context.ContextCompilationCache;
import com.dvarahq.agentworks.context.ContextCompiler;
import com.dvarahq.agentworks.context.SlidingWindowCompactionStrategy;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.agent.springai.SpringAiExecutorSettings;
import com.dvarahq.agentworks.agent.springai.ToolPermissionAuthority;
import com.dvarahq.agentworks.agent.springai.ToolInputGuardrail;
import com.dvarahq.agentworks.agent.springai.ToolOutputGuardrail;
import com.dvarahq.agentworks.agent.springai.ToolInteractionAuthority;
import com.dvarahq.agentworks.agent.springai.CapabilityToolCallback;
import com.dvarahq.agentworks.agent.springai.CapabilityToolCallbackProvider;
import com.dvarahq.agentworks.agent.springai.AgentAsToolCallback;
import com.dvarahq.agentworks.capability.ToolId;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.credential.JitCredentialToolInvoker;
import com.dvarahq.agentworks.storage.security.JdbcRememberedPermissionAuthority;
import com.dvarahq.agentworks.storage.interaction.JdbcHumanInteractionAuthority;
import com.dvarahq.agentworks.memory.KnowledgeProvider;
import com.dvarahq.agentworks.storage.memory.JdbcAgentMemoryStore;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Registers the stateless Spring AI executor (issue #34). It reaches models only through Core's {@link ModelGateway},
 * so it needs no application {@code ChatModel}: every invocation uses the release-pinned model. Tools are the
 * application's {@link ToolCallbackProvider} beans for legacy manifests and exact
 * {@link CapabilityToolCallbackProvider} bindings for version-3 manifests, both snapshotted once in order. Settings:
 * {@code agentworks.spring-ai.model-timeout} (for example 5m or PT5M; default 5m), {@code chunk-batch-chars} (default 2048),
 * {@code max-model-rounds} (default 16), and {@code write-drain-timeout} (default 30s: how long an ending continuation
 * waits for a stream write in progress before it abandons the claim for recovery). Durations need a unit.
 * {@code agentworks.spring-ai.enabled=false} disables it.
 */
@AutoConfiguration(after = {AgentWorksGatewayAutoConfiguration.class, AgentWorksLifecycleAutoConfiguration.class})
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass({ChatClient.class, ToolCallbackProvider.class})
@ConditionalOnBean(ModelGateway.class)
@ConditionalOnProperty(prefix = "agentworks.spring-ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class AgentWorksSpringAiAutoConfiguration {

    @Bean
    CapabilityToolCallbackProvider agentWorksAgentAsToolCapability() {
        return CapabilityToolCallbackProvider.from(new CapabilityToolCallback(
                new ToolId("agentworks", "delegate-agent", "1.0.0"), new AgentAsToolCallback()));
    }

    @Bean
    @ConditionalOnMissingBean(ReplayModelExecutor.class)
    ReplayModelExecutor agentWorksReplayModelExecutor(ModelGateway gateway, Environment environment) {
        return new GatewayReplayModelExecutor(gateway, settings(environment).modelTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(ContextCompilationCache.class)
    ContextCompilationCache agentWorksContextCompilationCache(Environment environment) {
        return new ContextCompilationCache(environment.getProperty("agentworks.context.cache.maximum-entries",
                Integer.class, 1024));
    }

    @Bean
    @ConditionalOnMissingBean(ContextCompiler.class)
    ContextCompiler agentWorksContextCompiler(ContextCompilationCache cache) {
        return new BasicContextCompiler(cache, new SlidingWindowCompactionStrategy());
    }

    @Bean
    @ConditionalOnMissingBean(SpringAiAgentExecutor.class)
    SpringAiAgentExecutor agentWorksSpringAiExecutor(ModelGateway gateway, ObjectProvider<ToolCallbackProvider> providers,
            ObjectProvider<CapabilityToolCallbackProvider> capabilityProviders,
            ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<ToolPermissionAuthority> configuredPermissions,
            ObjectProvider<ToolInputGuardrail> configuredGuardrails,
            ObjectProvider<ToolOutputGuardrail> configuredOutputGuardrails,
            ObjectProvider<JitCredentialToolInvoker> credentialInvokers,
            ObjectProvider<JdbcHumanInteractionAuthority> configuredInteractions,
            ObjectProvider<ArtifactStore> artifactStores,
            ObjectProvider<ContextCompiler> contextCompilers,
            ObjectProvider<JdbcAgentMemoryStore> memoryStores,
            ObjectProvider<KnowledgeProvider> knowledgeProviders,
            ObjectProvider<AgentWorksPlanes> configuredPlanes, Environment environment) {
        ToolCallback[] callbacks = providers.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
        CapabilityToolCallback[] capabilityCallbacks = capabilityProviders.orderedStream()
                .flatMap(provider -> Arrays.stream(provider.getCapabilityToolCallbacks()))
                .toArray(CapabilityToolCallback[]::new);
        ToolPermissionAuthority permissionAuthority = configuredPermissions.getIfUnique();
        AgentWorksPlanes planes = configuredPlanes.getIfUnique();
        if (permissionAuthority == null && planes != null) {
            AgentWorksPlanes.Plane run = planes.run();
            JdbcRememberedPermissionAuthority permissions = new JdbcRememberedPermissionAuthority(run.jdbc());
            permissionAuthority = request -> run.tenants().execute(
                    new com.dvarahq.agentworks.domain.tenant.WorkspaceId(request.workspaceId()),
                    () -> permissions.evaluate(request));
        }
        java.util.List<ToolInputGuardrail> guardrails = configuredGuardrails.orderedStream().toList();
        if (guardrails.size() > 1) {
            throw new IllegalStateException("exactly zero or one ToolInputGuardrail bean may be configured");
        }
        ToolInputGuardrail inputGuardrail = guardrails.isEmpty()
                ? ToolInputGuardrail.validatedInput() : guardrails.getFirst();
        java.util.List<ToolOutputGuardrail> outputGuardrails = configuredOutputGuardrails.orderedStream().toList();
        if (outputGuardrails.size() > 1) {
            throw new IllegalStateException("exactly zero or one ToolOutputGuardrail bean may be configured");
        }
        ToolOutputGuardrail outputGuardrail = outputGuardrails.isEmpty()
                ? ToolOutputGuardrail.validatedOutput() : outputGuardrails.getFirst();
        JdbcHumanInteractionAuthority interactions = configuredInteractions.getIfUnique();
        ToolInteractionAuthority interactionAuthority = interactions == null ? null
                : (workspace, run, key) -> interactions.findByIdempotency(
                        new com.dvarahq.agentworks.domain.tenant.WorkspaceId(workspace), run, key);
        return new SpringAiAgentExecutor(gateway, () -> callbacks.clone(), () -> capabilityCallbacks.clone(),
                observations.getIfUnique(() -> ObservationRegistry.NOOP), settings(environment), permissionAuthority,
                inputGuardrail, outputGuardrail, credentialInvokers.getIfUnique(), interactionAuthority,
                artifactStores.getIfUnique(), contextCompilers.getIfUnique(), memoryStores.getIfUnique(),
                knowledgeProviders.orderedStream().toList());
    }

    static SpringAiExecutorSettings settings(Environment environment) {
        SpringAiExecutorSettings defaults = SpringAiExecutorSettings.DEFAULTS;
        return new SpringAiExecutorSettings(AgentWorksRunWorkerAutoConfiguration.duration(environment,
                        "agentworks.spring-ai.model-timeout", defaults.modelTimeout()),
                environment.getProperty("agentworks.spring-ai.chunk-batch-chars", Integer.class,
                        defaults.chunkBatchChars()),
                environment.getProperty("agentworks.spring-ai.max-model-rounds", Integer.class,
                        defaults.maxModelRounds()),
                AgentWorksRunWorkerAutoConfiguration.duration(environment, "agentworks.spring-ai.write-drain-timeout",
                        defaults.writeDrainTimeout()));
    }
}
