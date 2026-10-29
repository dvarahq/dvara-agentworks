package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.observability.AgentWorksMeterPolicy;
import com.dvarahq.agentworks.observability.AgentWorksObservabilityProperties;
import com.dvarahq.agentworks.observability.DiagnosticsController;
import com.dvarahq.agentworks.observability.DiagnosticsExceptionHandler;
import com.dvarahq.agentworks.observability.RequiredDependenciesHealthIndicator;
import com.dvarahq.agentworks.observability.SupportBundleRepository;
import com.dvarahq.agentworks.observability.SupportBundleService;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** Production observability wiring. Exporters remain Spring Boot profile choices, not product dependencies. */
@AutoConfiguration(after = {AgentWorksJdbcAutoConfiguration.class, AgentWorksSecurityAutoConfiguration.class})
@EnableConfigurationProperties(AgentWorksObservabilityProperties.class)
public class AgentWorksObservabilityAutoConfiguration {
    @Bean
    AgentWorksMeterPolicy agentWorksMeterPolicy(AgentWorksObservabilityProperties properties) {
        properties.validate();
        return new AgentWorksMeterPolicy(properties.getMaxTagValues());
    }

    @Bean
    MeterBinder agentWorksObservabilityPolicyMetrics(AgentWorksMeterPolicy policy) {
        return registry -> Gauge.builder("agentworks.observability.cardinality.denied", policy,
                        ignored -> policy.denied())
                .description("AgentWorks meter series denied by the identity/cardinality policy")
                .baseUnit("series").register(registry);
    }

    @Bean(name = "agentWorksRequiredDependenciesHealthIndicator")
    @ConditionalOnMissingBean(name = "agentWorksRequiredDependenciesHealthIndicator")
    RequiredDependenciesHealthIndicator agentWorksRequiredDependenciesHealthIndicator(JdbcTemplate jdbc,
            AgentWorksObservabilityProperties properties) {
        properties.validate();
        return new RequiredDependenciesHealthIndicator(jdbc, properties.getRequiredDependencies());
    }

    @Bean
    @ConditionalOnMissingBean
    SupportBundleRepository agentWorksSupportBundleRepository(JdbcTemplate jdbc,
            TenantTransactionTemplate transactions) {
        return new SupportBundleRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean
    SupportBundleService agentWorksSupportBundleService(SupportBundleRepository repository,
            AgentWorksObservabilityProperties properties, ObjectProvider<Clock> clocks) {
        properties.validate();
        return new SupportBundleService(repository, properties.getSupportBundle(),
                clocks.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(WorkspaceScopeResolver.class)
    DiagnosticsController agentWorksDiagnosticsController(WorkspaceScopeResolver workspaces,
            SupportBundleService bundles) {
        return new DiagnosticsController(workspaces, bundles);
    }

    @Bean
    @ConditionalOnMissingBean
    DiagnosticsExceptionHandler agentWorksDiagnosticsExceptionHandler() {
        return new DiagnosticsExceptionHandler();
    }
}
