package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialProviderRegistration;
import com.dvarahq.agentworks.credential.CredentialProviderRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Composes independently contributed credential schemes without inventing credential authority. */
@AutoConfiguration
@AutoConfigureBefore(AgentWorksCredentialAutoConfiguration.class)
@ConditionalOnProperty(prefix = "agentworks.credentials", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class AgentWorksCredentialProviderAutoConfiguration {

    @Bean
    @ConditionalOnBean(CredentialProviderRegistration.class)
    @ConditionalOnMissingBean(CredentialProvider.class)
    CredentialProvider agentWorksCredentialProvider(
            ObjectProvider<CredentialProviderRegistration> registrations) {
        return new CredentialProviderRegistry(registrations.orderedStream().toList());
    }
}
