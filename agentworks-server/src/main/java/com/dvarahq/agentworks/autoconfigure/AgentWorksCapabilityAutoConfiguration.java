package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.capability.CapabilityCatalogAdministration;
import com.dvarahq.agentworks.capability.CapabilityPackRegistry;
import com.dvarahq.agentworks.capability.CapabilityRuntimeCatalog;
import com.dvarahq.agentworks.capability.DeterministicCapabilityResolver;
import com.dvarahq.agentworks.capability.ToolRegistry;
import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.capability.CapabilityCatalogService;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityApiExceptionHandler;
import com.dvarahq.agentworks.controlplane.capability.web.CapabilityCatalogController;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.runplane.execution.RunCapabilityResolver;
import com.dvarahq.agentworks.mcp.McpConnectionHealthSource;
import com.dvarahq.agentworks.mcp.web.McpConnectionController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Wires the issue #38 capability catalog on the primary Spring JDBC transaction boundary. */
@AutoConfiguration(after = {AgentWorksJdbcAutoConfiguration.class, AgentWorksAgentDefinitionAutoConfiguration.class,
        AgentWorksSecurityAutoConfiguration.class})
public final class AgentWorksCapabilityAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({TenantTransactionTemplate.class, CapabilityRuntimeCatalog.class})
    RunCapabilityResolver agentWorksRunCapabilityResolver(TenantTransactionTemplate transactions,
            CapabilityRuntimeCatalog catalog, Environment environment) {
        RunCapabilityResolver.Settings defaults = RunCapabilityResolver.Settings.DEFAULTS;
        String[] compatibility = environment.getProperty("agentworks.capabilities.runtime.compatibility",
                String[].class, defaults.compatibility().toArray(String[]::new));
        Set<String> facts = Arrays.stream(compatibility).map(String::strip).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        var settings = new RunCapabilityResolver.Settings(facts,
                environment.getProperty("agentworks.capabilities.runtime.summary-budget-tokens", Integer.class,
                        defaults.summaryBudgetTokens()),
                environment.getProperty("agentworks.capabilities.runtime.detail-budget-tokens", Integer.class,
                        defaults.detailBudgetTokens()));
        return new RunCapabilityResolver(transactions, catalog, new DeterministicCapabilityResolver(), settings);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({TenantTransactionTemplate.class, ToolRegistry.class, CapabilityPackRegistry.class,
            CapabilityCatalogAdministration.class, DatabaseClock.class})
    CapabilityCatalogService agentWorksCapabilityCatalogService(TenantTransactionTemplate transactions,
            ToolRegistry tools, CapabilityPackRegistry packs, CapabilityCatalogAdministration administration,
            DatabaseClock clock) {
        return new CapabilityCatalogService(transactions, tools, packs, administration, clock);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class CapabilityApi {
        @Bean
        @ConditionalOnMissingBean
        CapabilityCatalogController agentWorksCapabilityCatalogController(CapabilityCatalogService catalog,
                WorkspaceScopeResolver workspaces, javax.sql.DataSource dataSource,
                org.springframework.transaction.support.TransactionTemplate transactions,
                org.springframework.beans.factory.ObjectProvider<java.time.Clock> clocks) {
            return new CapabilityCatalogController(catalog, workspaces, AgentWorksSecurityAutoConfiguration
                    .controlActionAudit(dataSource, transactions, clocks.getIfAvailable(java.time.Clock::systemUTC),
                            com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry.standard()));
        }

        @Bean
        CapabilityApiExceptionHandler agentWorksCapabilityApiExceptionHandler() {
            return new CapabilityApiExceptionHandler();
        }

        @Bean
        @ConditionalOnMissingBean
        McpConnectionController agentWorksMcpConnectionController(
                org.springframework.beans.factory.ObjectProvider<McpConnectionHealthSource> sources,
                WorkspaceScopeResolver workspaces) {
            return new McpConnectionController(sources.orderedStream().toList(), workspaces);
        }
    }
}
