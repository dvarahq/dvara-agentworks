package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.artifact.ArtifactCompute;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

final class AgentWorksArtifactAutoConfigurationTest {
    @TempDir
    Path root;

    private final ApplicationContextRunner context = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class, TransactionAutoConfiguration.class,
                    AgentWorksJdbcAutoConfiguration.class, AgentWorksArtifactAutoConfiguration.class));

    @Test
    void backendIsOptIn() {
        context.run(result -> assertThat(result).doesNotHaveBean(ArtifactStore.class));
    }

    @Test
    void configuredBackendUsesLocalTransactionManagerAndCreatesPrivateNamespaces() {
        context.withPropertyValues("agentworks.artifacts.enabled=true", "agentworks.artifacts.root=" + root,
                        "agentworks.artifacts.maximum-size=1MB")
                .run(result -> {
                    assertThat(result).hasSingleBean(ArtifactStore.class);
                    assertThat(result).hasSingleBean(ArtifactCompute.class);
                    assertThat(root.resolve("objects")).isDirectory();
                    assertThat(root.resolve("staging")).isDirectory();
                });
    }
}
