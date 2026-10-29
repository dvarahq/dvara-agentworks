package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver;
import com.dvarahq.agentworks.controlplane.security.RememberedPermissionService;
import com.dvarahq.agentworks.controlplane.security.web.RememberedPermissionApiExceptionHandler;
import com.dvarahq.agentworks.controlplane.security.web.RememberedPermissionController;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.security.JdbcRememberedPermissionAuthority;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/** Wires the issue #39 remembered-permission management boundary on the Control Plane data source. */
@AutoConfiguration(after = {AgentWorksJdbcAutoConfiguration.class, AgentWorksSecurityAutoConfiguration.class})
public final class AgentWorksPermissionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(TenantTransactionTemplate.class)
    RememberedPermissionService agentWorksRememberedPermissionService(TenantTransactionTemplate transactions,
            DataSource dataSource) {
        return new RememberedPermissionService(transactions,
                new JdbcRememberedPermissionAuthority(new JdbcTemplate(dataSource)));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class PermissionApi {
        @Bean
        @ConditionalOnMissingBean
        RememberedPermissionController agentWorksRememberedPermissionController(RememberedPermissionService service,
                WorkspaceScopeResolver workspaces, DataSource dataSource, TransactionTemplate transactions,
                LifecycleRetry retry, org.springframework.beans.factory.ObjectProvider<Clock> clocks) {
            return new RememberedPermissionController(service, workspaces, AgentWorksSecurityAutoConfiguration
                    .controlActionAudit(dataSource, transactions, clocks.getIfAvailable(Clock::systemUTC), retry));
        }

        @Bean
        RememberedPermissionApiExceptionHandler agentWorksRememberedPermissionApiExceptionHandler() {
            return new RememberedPermissionApiExceptionHandler();
        }
    }
}
