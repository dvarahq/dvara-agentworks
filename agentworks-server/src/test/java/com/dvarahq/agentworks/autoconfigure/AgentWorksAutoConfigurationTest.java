package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.agent.AgentExecutor;
import com.dvarahq.agentworks.agent.springai.SpringAiAgentExecutor;
import com.dvarahq.agentworks.agent.springai.ToolInputGuardrail;
import com.dvarahq.agentworks.agent.springai.ToolOutputGuardrail;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialProviderRegistration;
import com.dvarahq.agentworks.credential.JitCredentialToolInvoker;
import com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcRunAdmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorksAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentWorksSpringAiAutoConfiguration.class,
                    AgentWorksJdbcAutoConfiguration.class, AgentWorksCredentialProviderAutoConfiguration.class,
                    AgentWorksCredentialAutoConfiguration.class,
                    AgentWorksCapabilityAutoConfiguration.class));

    private static ChatModel model() {
        return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("configured"))));
    }

    private static com.dvarahq.agentworks.gateway.ModelGateway gateway() {
        return new com.dvarahq.agentworks.gateway.ModelGateway(com.dvarahq.agentworks.gateway.GatewayRegistry.empty(),
                null, null, event -> { }, java.time.Clock.systemUTC());
    }

    @Test
    void absentCollaboratorsDoNotCreateServices() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(AgentExecutor.class)
                    .doesNotHaveBean(JdbcLifecycleRepository.class).doesNotHaveBean(JdbcRunAdmissionRepository.class)
                    .doesNotHaveBean(JitCredentialToolInvoker.class);
        });
    }

    // Issue #34: the executor reaches models only through Core's gateway, configured by properties.
    @Test
    void theGatewayCreatesTheExecutorWithConfiguredSettings() {
        runner.withBean(com.dvarahq.agentworks.gateway.ModelGateway.class, AgentWorksAutoConfigurationTest::gateway)
                .withPropertyValues("agentworks.spring-ai.model-timeout=PT45S",
                        "agentworks.spring-ai.chunk-batch-chars=512", "agentworks.spring-ai.max-model-rounds=4")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SpringAiAgentExecutor.class);
                    assertThat(context.getBean(AgentExecutor.class).type()).isEqualTo(SpringAiAgentExecutor.TYPE);
                    assertThat(AgentWorksSpringAiAutoConfiguration.settings(context.getEnvironment())).isEqualTo(
                            new com.dvarahq.agentworks.agent.springai.SpringAiExecutorSettings(
                                    java.time.Duration.ofSeconds(45), 512, 4));
                });
    }

    @Test
    void multipleInputGuardrailsFailStartupInsteadOfChoosingImplicitly() {
        runner.withBean(com.dvarahq.agentworks.gateway.ModelGateway.class, AgentWorksAutoConfigurationTest::gateway)
                .withBean("firstGuardrail", ToolInputGuardrail.class, ToolInputGuardrail::validatedInput)
                .withBean("secondGuardrail", ToolInputGuardrail.class, ToolInputGuardrail::validatedInput)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "exactly zero or one ToolInputGuardrail bean may be configured");
                });
    }

    @Test
    void multipleOutputGuardrailsFailStartupInsteadOfChoosingImplicitly() {
        runner.withBean(com.dvarahq.agentworks.gateway.ModelGateway.class, AgentWorksAutoConfigurationTest::gateway)
                .withBean("firstOutputGuardrail", ToolOutputGuardrail.class,
                        ToolOutputGuardrail::validatedOutput)
                .withBean("secondOutputGuardrail", ToolOutputGuardrail.class,
                        ToolOutputGuardrail::validatedOutput)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "exactly zero or one ToolOutputGuardrail bean may be configured");
                });
    }

    // Issue #34: an application ChatModel is never a way around the gateway and its release pins.
    @Test
    void anApplicationChatModelAloneCreatesNoExecutor() {
        runner.withBean(ChatModel.class, AgentWorksAutoConfigurationTest::model)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AgentExecutor.class));
    }

    // Executors of other types coexist with the Spring AI executor, so each run's pinned type stays routable.
    @Test
    void executorsOfOtherTypesCoexistAndACustomSpringAiExecutorWins() {
        AgentExecutor custom = () -> "custom";
        runner.withBean(com.dvarahq.agentworks.gateway.ModelGateway.class, AgentWorksAutoConfigurationTest::gateway)
                .withBean(AgentExecutor.class, () -> custom).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SpringAiAgentExecutor.class);
                    assertThat(context.getBeansOfType(AgentExecutor.class)).hasSize(2);
                });
        SpringAiAgentExecutor mine = new SpringAiAgentExecutor(gateway(),
                () -> new org.springframework.ai.tool.ToolCallback[0], io.micrometer.observation.ObservationRegistry.NOOP,
                com.dvarahq.agentworks.agent.springai.SpringAiExecutorSettings.DEFAULTS);
        runner.withBean(com.dvarahq.agentworks.gateway.ModelGateway.class, AgentWorksAutoConfigurationTest::gateway)
                .withBean(SpringAiAgentExecutor.class, () -> mine).run(context ->
                        assertThat(context.getBean(SpringAiAgentExecutor.class)).isSameAs(mine));
    }

    @Test
    void missingSpringAiClassesBackOff() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.ai"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("agentWorksSpringAiExecutor"));
    }

    @Test
    void missingJdbcClassesBackOff() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.jdbc"))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("agentWorksLifecycleStore"));
    }

    @Test
    void jdbcCollaboratorsWireStoresWithoutOpeningConnections() {
        runner.withUserConfiguration(JdbcCollaborators.class).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JdbcLifecycleRepository.class)
                    .hasSingleBean(JdbcIdempotencyRepository.class).hasSingleBean(JdbcRunAdmissionRepository.class)
                    .hasSingleBean(com.dvarahq.agentworks.runplane.execution.RunCapabilityResolver.class);
        });
    }

    @Test
    void aNonJdbcTransactionManagerFailsTheJdbcContractAtStartup() {
        DataSource source = new DriverManagerDataSource();
        runner.withBean(DataSource.class, () -> source)
                .withBean(TransactionTemplate.class, () -> new TransactionTemplate(nonJdbcManager()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "AgentWorks JDBC requires DataSourceTransactionManager or JdbcTransactionManager; "
                                    + "JPA and JTA transaction managers are not supported");
                });
    }

    @Test
    void aJdbcTransactionManagerForAnotherDataSourceFailsTheJdbcContractAtStartup() {
        DataSource selected = new DriverManagerDataSource();
        DataSource other = new DriverManagerDataSource();
        runner.withBean(DataSource.class, () -> selected)
                .withBean(TransactionTemplate.class,
                        () -> new TransactionTemplate(new JdbcTransactionManager(other)))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "AgentWorks JDBC requires its TransactionTemplate and DataSource to refer to the same "
                                    + "DataSource instance");
                });
    }

    @Test
    void datasourceWithoutTransactionsWiresNoTransactionalStore() {
        runner.withBean(DataSource.class, DriverManagerDataSource::new).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(JdbcLifecycleRepository.class)
                    .doesNotHaveBean(JdbcRunAdmissionRepository.class);
        });
    }

    @Test
    void customStoreWins() {
        DataSource source = new DriverManagerDataSource();
        JdbcLifecycleRepository custom = new JdbcLifecycleRepository(new org.springframework.jdbc.core.JdbcTemplate(source));
        runner.withUserConfiguration(JdbcCollaborators.class).withBean(JdbcLifecycleRepository.class, () -> custom)
                .run(context -> assertThat(context.getBean(JdbcLifecycleRepository.class)).isSameAs(custom));
    }

    @Test
    void ambiguousDatasourcesBackOff() {
        runner.withBean("firstSource", DataSource.class, DriverManagerDataSource::new)
                .withBean("anotherSource", DataSource.class, DriverManagerDataSource::new)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JdbcLifecycleRepository.class)
                        .doesNotHaveBean(JdbcRunAdmissionRepository.class));
    }

    @Test
    void credentialInvokerRequiresAuditSink() {
        runner.withBean(CredentialProvider.class, AgentWorksAutoConfigurationTest::credentialProvider)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(JitCredentialToolInvoker.class));
    }

    @Test
    void explicitCredentialAuthorityAndAuditEnableInvoker() {
        runner.withBean(CredentialProvider.class, AgentWorksAutoConfigurationTest::credentialProvider)
                .withBean(CredentialAuditSink.class, () -> event -> {})
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(JitCredentialToolInvoker.class));
    }

    @Test
    void credentialRegistrationsComposeIntoOneProviderAndEnableTheInvoker() {
        runner.withBean("environmentCredentials", CredentialProviderRegistration.class,
                        () -> new CredentialProviderRegistration("env", credentialProvider()))
                .withBean("fileCredentials", CredentialProviderRegistration.class,
                        () -> new CredentialProviderRegistration("file", credentialProvider()))
                .withBean(CredentialAuditSink.class, () -> event -> {})
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(CredentialProvider.class)
                        .hasSingleBean(JitCredentialToolInvoker.class)
                        .getBean(CredentialProvider.class).hasToString(
                                "CredentialProviderRegistry[schemes=[env, file]]"));
    }

    @Test
    void customCredentialProviderWinsOverRegistrations() {
        CredentialProvider custom = credentialProvider();
        runner.withBean(CredentialProvider.class, () -> custom)
                .withBean(CredentialProviderRegistration.class,
                        () -> new CredentialProviderRegistration("env", credentialProvider()))
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(CredentialProvider.class).isSameAs(custom));
    }

    @Test
    void duplicateCredentialSchemesFailStartup() {
        runner.withBean("firstCredentials", CredentialProviderRegistration.class,
                        () -> new CredentialProviderRegistration("env", credentialProvider()))
                .withBean("secondCredentials", CredentialProviderRegistration.class,
                        () -> new CredentialProviderRegistration("env", credentialProvider()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "duplicate credential provider scheme: env");
                });
    }

    @Test
    void propertiesDisableIntegrations() {
        runner.withUserConfiguration(JdbcCollaborators.class)
                .withBean(com.dvarahq.agentworks.gateway.ModelGateway.class, AgentWorksAutoConfigurationTest::gateway)
                .withBean(CredentialProvider.class, AgentWorksAutoConfigurationTest::credentialProvider)
                .withBean(CredentialAuditSink.class, () -> event -> {})
                .withPropertyValues("agentworks.spring-ai.enabled=false", "agentworks.jdbc.enabled=false",
                        "agentworks.credentials.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(AgentExecutor.class)
                        .doesNotHaveBean(JdbcLifecycleRepository.class).doesNotHaveBean(JdbcRunAdmissionRepository.class)
                        .doesNotHaveBean(JitCredentialToolInvoker.class));
    }

    @Test
    void modelAutoConfigurationIsProcessedBeforeAgentWorks() {
        runner.withConfiguration(AutoConfigurations.of(ModelAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SpringAiAgentExecutor.class));
    }

    @Test
    void bootDiscoversImportsWithoutComponentScanningOrExplicitAgentWorksImport() {
        new ApplicationContextRunner().withUserConfiguration(ConsumerApplication.class)
                .withPropertyValues("agentworks.compatibility.allow-embedded-database=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SpringAiAgentExecutor.class)
                        .hasSingleBean(JdbcLifecycleRepository.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ConsumerApplication {}

    @AutoConfiguration
    static class ModelAutoConfiguration {
        @Bean
        com.dvarahq.agentworks.gateway.ModelGateway modelGateway() { return gateway(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class JdbcCollaborators {
        @Bean
        DataSource dataSource() { return new DriverManagerDataSource(); }
        @Bean
        TransactionTemplate transactions(DataSource dataSource) {
            return new TransactionTemplate(new JdbcTransactionManager(dataSource));
        }
    }

    private static PlatformTransactionManager nonJdbcManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {}

            @Override
            public void rollback(TransactionStatus status) {}
        };
    }

    private static CredentialProvider credentialProvider() {
        return (CredentialProvider) Proxy.newProxyInstance(CredentialProvider.class.getClassLoader(),
                new Class<?>[]{CredentialProvider.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Auto-configuration must never resolve credentials");
                });
    }
}
