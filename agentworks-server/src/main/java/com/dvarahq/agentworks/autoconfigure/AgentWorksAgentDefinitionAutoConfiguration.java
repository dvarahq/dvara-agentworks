package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.agent.AgentDefinitionService;
import com.dvarahq.agentworks.controlplane.agent.web.AgentApiExceptionHandler;
import com.dvarahq.agentworks.controlplane.agent.web.AgentDefinitionController;
import com.dvarahq.agentworks.controlplane.agent.web.AuthenticatedWorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.spec.AgentSpecCodec;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * Wires AgentSpec validation, AgentDefinition/AgentVersion services, and their REST API. The default
 * {@link WorkspaceScopeResolver} returns the workspace bound to the authenticated principal (issue #74) and fails
 * closed without one. The unauthenticated local-development access of issue #155
 * ({@code agentworks.security.unsafe-local-access}) is removed; enabling it fails startup rather than being ignored.
 */
@AutoConfiguration(after = AgentWorksJdbcAutoConfiguration.class)
@ConditionalOnProperty(prefix = "agentworks.agents", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class AgentWorksAgentDefinitionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AgentSpecCodec agentWorksAgentSpecCodec() {
        return AgentSpecCodec.create();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({TenantTransactionTemplate.class, JdbcAgentDefinitionRepository.class})
    AgentDefinitionService agentWorksAgentDefinitionService(TenantTransactionTemplate tenants,
            JdbcAgentDefinitionRepository repository, AgentSpecCodec codec) {
        return new AgentDefinitionService(tenants, repository, codec, Clock.systemUTC());
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnBean({TenantTransactionTemplate.class, JdbcAgentDefinitionRepository.class})
    static class AgentDefinitionApi {

        @Bean
        @ConditionalOnMissingBean
        WorkspaceScopeResolver agentWorksWorkspaceScopeResolver(Environment environment) {
            if (environment.getProperty("agentworks.security.unsafe-local-access", Boolean.class, false)) {
                throw new IllegalStateException("agentworks.security.unsafe-local-access was removed by issue #74; "
                        + "every request now authenticates. Create the first administrator with the bootstrap token.");
            }
            return new AuthenticatedWorkspaceScopeResolver();
        }

        @Bean
        @ConditionalOnMissingBean
        AgentDefinitionController agentWorksAgentDefinitionController(AgentDefinitionService agents,
                WorkspaceScopeResolver workspaces, javax.sql.DataSource dataSource,
                org.springframework.transaction.support.TransactionTemplate transactions,
                org.springframework.beans.factory.ObjectProvider<java.time.Clock> clocks) {
            // The definition service writes through the primary data source and its transaction template.
            return new AgentDefinitionController(agents, workspaces, AgentWorksSecurityAutoConfiguration
                    .controlActionAudit(dataSource, transactions, clocks.getIfAvailable(java.time.Clock::systemUTC),
                            com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard()));
        }

        @Bean
        AgentApiExceptionHandler agentWorksAgentApiExceptionHandler() {
            return new AgentApiExceptionHandler();
        }
    }
}
