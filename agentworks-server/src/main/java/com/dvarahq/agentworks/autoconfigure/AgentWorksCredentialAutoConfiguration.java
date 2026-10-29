package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.JitCredentialToolInvoker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.time.Clock;

/** No default credential authority or audit sink is supplied. */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnSingleCandidate(CredentialProvider.class)
@ConditionalOnProperty(prefix = "agentworks.credentials", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class AgentWorksCredentialAutoConfiguration {

    @Bean
    @ConditionalOnSingleCandidate(CredentialAuditSink.class)
    @ConditionalOnMissingBean
    JitCredentialToolInvoker agentWorksCredentialToolInvoker(CredentialProvider provider,
            CredentialAuditSink auditSink, ObjectProvider<Clock> clocks) {
        return new JitCredentialToolInvoker(provider, auditSink, clocks.getIfUnique(Clock::systemUTC));
    }
}
