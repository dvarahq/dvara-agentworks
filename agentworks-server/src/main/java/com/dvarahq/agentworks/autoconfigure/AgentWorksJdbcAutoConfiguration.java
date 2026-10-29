package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.storage.DefaultTenantBootstrap;
import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import com.dvarahq.agentworks.storage.JdbcWorkspaceRepository;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.capability.JdbcCapabilityRegistry;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcRunAdmissionRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.storage.memory.JdbcAgentMemoryStore;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/** Wires JDBC stores and the optional default-tenant bootstrap. Dispatch and worker scheduling remain explicit. */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration"})
@ConditionalOnClass({JdbcTemplate.class, TransactionTemplate.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "agentworks.jdbc", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class AgentWorksJdbcAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnSingleCandidate(TransactionTemplate.class)
    static class TransactionalStores {

        TransactionalStores(DataSource dataSource, TransactionTemplate transactions) {
            AgentWorksJdbcTransactionContract.require(dataSource, transactions);
        }

        @Bean
        @ConditionalOnMissingBean
        TenantTransactionTemplate agentWorksTenantTransactions(TransactionTemplate transactions) {
            return new TenantTransactionTemplate(transactions);
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcWorkspaceRepository agentWorksWorkspaceRepository(DataSource dataSource) {
            return new JdbcWorkspaceRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcAgentDefinitionRepository agentWorksAgentDefinitionRepository(DataSource dataSource) {
            return new JdbcAgentDefinitionRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcCapabilityRegistry agentWorksCapabilityRegistry(DataSource dataSource) {
            return new JdbcCapabilityRegistry(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        DefaultTenantBootstrap agentWorksDefaultTenantBootstrap(DataSource dataSource,
                TransactionTemplate transactions) {
            return new DefaultTenantBootstrap(new JdbcTemplate(dataSource), transactions);
        }

        @Bean
        @ConditionalOnProperty(prefix = "agentworks.tenancy", name = "bootstrap-default", havingValue = "true")
        ApplicationRunner agentWorksDefaultTenantBootstrapRunner(DefaultTenantBootstrap bootstrap) {
            return arguments -> bootstrap.initialize();
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcIdentityRepository agentWorksIdentityRepository(DataSource dataSource) {
            return new JdbcIdentityRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcLifecycleRepository agentWorksLifecycleRepository(DataSource dataSource) {
            return new JdbcLifecycleRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcIdempotencyRepository agentWorksIdempotencyRepository(DataSource dataSource) {
            return new JdbcIdempotencyRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcRunAdmissionRepository agentWorksRunAdmissionRepository(DataSource dataSource) {
            return new JdbcRunAdmissionRepository(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        JdbcAgentMemoryStore agentWorksMemoryStore(DataSource dataSource, TenantTransactionTemplate tenants) {
            return new JdbcAgentMemoryStore(new JdbcTemplate(dataSource), tenants);
        }

        @Bean
        @ConditionalOnMissingBean
        DatabaseClock agentWorksDatabaseClock(DataSource dataSource) {
            return new DatabaseClock(new JdbcTemplate(dataSource));
        }

        @Bean
        @ConditionalOnMissingBean
        LifecycleRetry agentWorksLifecycleRetry() {
            return LifecycleRetry.standard();
        }
    }
}
