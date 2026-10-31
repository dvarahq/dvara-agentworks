package com.dvarahq.agentworks.journey.extension;

import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.agent.springai.CapabilityToolCallback;
import com.dvarahq.agentworks.agent.springai.CapabilityToolCallbackProvider;
import com.dvarahq.agentworks.capability.ToolId;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Issue #37's test-only harness extension. The journey puts this jar on the unmodified server boot jar's
 * {@code loader.path} and enables it with {@code journey.extension.enabled=true}. It contributes, through
 * public extension points only:
 * <ul>
 *   <li>a {@link ConfiguredGateway} {@code journey}: the deterministic fake adapter with the {@link JourneyScript}. The
 *       server's own gateway configuration checks it like any contributed gateway, so it is refused outside the
 *       loopback local-development gate ({@code agentworks.development.local});</li>
 *   <li>an exact {@link CapabilityToolCallbackProvider} binding for the one reference tool,
 *       {@link JourneyLedgerTool}.</li>
 * </ul>
 * It adds no endpoint, bean override, or database access: releases, deployments, and runs still go through the
 * server's own authority.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "journey.extension", name = "enabled", havingValue = "true")
public class JourneyExtensionAutoConfiguration {
    public static final String GATEWAY = "journey";

    @Bean
    ConfiguredGateway journeyGateway(Environment environment) {
        Map<String, String> models = new LinkedHashMap<>();
        models.put("reference", "journey-model-a");
        models.put("second", "journey-model-b");
        DeterministicFakeGatewayAdapter adapter = DeterministicFakeGatewayAdapter.builder(GATEWAY).models(models)
                .script(new JourneyScript(path(environment, "journey.hold-file"))).build();
        return new ConfiguredGateway(adapter, CapabilityPolicy.DEFAULT, null);
    }

    @Bean
    CapabilityToolCallbackProvider journeyTools(Environment environment) {
        var callback = new JourneyLedgerTool(path(environment, "journey.effects-file"));
        return CapabilityToolCallbackProvider.from(new CapabilityToolCallback(
                new ToolId("journey", "journey-ledger", "1.0.0"), callback));
    }

    private static Path path(Environment environment, String property) {
        return Path.of(environment.getRequiredProperty(property));
    }
}
