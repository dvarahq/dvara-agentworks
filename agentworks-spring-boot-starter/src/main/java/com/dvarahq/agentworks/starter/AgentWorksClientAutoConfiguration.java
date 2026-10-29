package com.dvarahq.agentworks.starter;

import com.dvarahq.agentworks.sdk.AgentWorksClient;
import com.dvarahq.agentworks.sdk.extension.AgentWorksExtension;
import com.dvarahq.agentworks.sdk.extension.ExtensionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

import java.util.List;

/** Opt-in autoconfiguration; construction performs no network or credential access. */
@AutoConfiguration
@EnableConfigurationProperties(AgentWorksClientProperties.class)
@ConditionalOnProperty(prefix = "agentworks.client", name = "enabled", havingValue = "true")
public class AgentWorksClientAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    AgentWorksClient agentWorksClient(AgentWorksClientProperties properties,
                                      ObjectProvider<CredentialReferenceResolver> resolvers) {
        AgentWorksClient.Builder builder = AgentWorksClient.builder(properties.getBaseUrl())
                .requestTimeout(properties.getRequestTimeout());
        CredentialReferenceResolver resolver = resolvers.getIfAvailable();
        String reference = properties.getCredentialRef();
        if (reference != null && !reference.isBlank()) {
            builder.accessTokenProvider(resolver == null
                    ? () -> { throw new IllegalStateException("credential resolver unavailable for configured reference"); }
                    : () -> resolver.resolve(reference));
        }
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean
    ExtensionRegistry agentWorksExtensionRegistry(ObjectProvider<AgentWorksExtension> extensions) {
        List<AgentWorksExtension> explicit = extensions.orderedStream().toList();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return ExtensionRegistry.load(loader == null ? getClass().getClassLoader() : loader, explicit);
    }

    @Bean(name = "agentWorksClientHealthIndicator")
    @ConditionalOnMissingBean(name = "agentWorksClientHealthIndicator")
    HealthIndicator agentWorksClientHealthIndicator(AgentWorksClientProperties properties,
                                                     ObjectProvider<CredentialReferenceResolver> resolvers) {
        return new AgentWorksClientHealthIndicator(properties, resolvers.getIfAvailable() != null);
    }
}
