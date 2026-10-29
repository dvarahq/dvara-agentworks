package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.artifact.ArtifactCompute;
import com.dvarahq.agentworks.artifact.ArtifactContentScanner;
import com.dvarahq.agentworks.artifact.ArtifactLifecycle;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.compute.ArtifactComputeLimits;
import com.dvarahq.agentworks.artifact.compute.BoundedArtifactCompute;
import com.dvarahq.agentworks.storage.artifact.JdbcFileArtifactStore;
import com.dvarahq.agentworks.storage.artifact.BoundedArtifactContentScanner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/** Opt-in OSS artifact backend using the application's local JDBC transaction manager and shared filesystem. */
@AutoConfiguration(after = AgentWorksJdbcAutoConfiguration.class)
@ConditionalOnClass({JdbcTemplate.class, TransactionTemplate.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "agentworks.artifacts", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentWorksArtifactProperties.class)
public final class AgentWorksArtifactAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(com.dvarahq.agentworks.identity.ControlActionAudit.class)
    com.dvarahq.agentworks.identity.ControlActionAudit agentWorksArtifactControlActionAudit(DataSource dataSource,
            TransactionTemplate transactions, ObjectProvider<Clock> clocks,
            com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry retry) {
        return AgentWorksSecurityAutoConfiguration.controlActionAudit(dataSource, transactions,
                clocks.getIfAvailable(Clock::systemUTC), retry);
    }

    @Bean
    @ConditionalOnMissingBean
    ArtifactContentScanner agentWorksArtifactContentScanner(AgentWorksArtifactProperties properties) {
        return new BoundedArtifactContentScanner(properties.getMaximumArchiveEntries(),
                properties.getMaximumArchiveExpandedSize().toBytes(),
                properties.getMaximumArchiveExpansionRatio());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnSingleCandidate(TransactionTemplate.class)
    JdbcFileArtifactStore agentWorksArtifactStore(DataSource dataSource, TransactionTemplate transactions,
            AgentWorksArtifactProperties properties, ArtifactContentScanner scanner) {
        return new JdbcFileArtifactStore(new JdbcTemplate(dataSource), transactions, properties.getRoot(),
                properties.getMaximumSize().toBytes(), java.time.Clock.systemUTC(), scanner,
                properties.getStagingLease(), properties.getDeletionGrace());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agentworks.artifacts.maintenance", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    ArtifactMaintenanceLifecycle agentWorksArtifactMaintenance(ArtifactLifecycle artifacts,
            AgentWorksArtifactProperties properties,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> metrics) {
        return new ArtifactMaintenanceLifecycle(artifacts, properties.getMaintenanceInterval(),
                properties.getMaintenanceBatchSize(), metrics.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    ArtifactCompute agentWorksArtifactCompute(ArtifactStore store, AgentWorksArtifactProperties properties) {
        long outputBytes = properties.getComputeMaximumOutputSize().toBytes();
        if (outputBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("agentworks.artifacts.compute-maximum-output-size is too large");
        }
        ArtifactComputeLimits limits = new ArtifactComputeLimits(properties.getComputeMaximumInputs(),
                properties.getComputeMaximumInputSize().toBytes(), (int) outputBytes,
                properties.getComputeTimeout());
        return new BoundedArtifactCompute(store, limits);
    }
}
