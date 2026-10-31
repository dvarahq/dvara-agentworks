package com.dvarahq.agentworks.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorksConfigurationEnvironmentPostProcessorTest {
    private final AgentWorksConfigurationEnvironmentPostProcessor processor =
            new AgentWorksConfigurationEnvironmentPostProcessor();

    @Test
    void catalogueIsVersionedCompleteAndUnambiguousForRepresentativeDynamicKeys() {
        AgentWorksConfigurationCatalog catalog = AgentWorksConfigurationCatalog.load();

        assertThat(catalog.schemaVersion()).isEqualTo(1);
        assertThat(catalog.entries()).hasSizeGreaterThan(70);
        assertThat(catalog.entries().stream().map(AgentWorksConfigurationCatalog.Entry::name))
                .doesNotHaveDuplicates();
        assertThat(catalog.find("agentworks.gateway.gateways.primary.models.0.alias").owner()).isEqualTo("gateway");
        assertThat(catalog.find("agentworks.observability.required-dependencies[0]").type())
                .isEqualTo("string-list");
        assertThat(catalog.find("agentworks.gateway.gateways.primary.capabilities[0]").type())
                .isEqualTo("string-list");
        assertThat(catalog.find("AGENTWORKS_RUNS_WORKER_ENABLED").name())
                .isEqualTo("agentworks.runs.worker.enabled");
        assertThat(catalog.find("agentworks.security.unsafe-local-access").status()).isEqualTo("removed");
    }

    @Test
    void unknownAndMisspelledKeysFailBeforeContextCreation() {
        MockEnvironment environment = environment("agentworks.runs.worker.threds", "4");

        assertThatThrownBy(() -> validate(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown AgentWorks configuration key")
                .hasMessageContaining("agentworks.runs.worker.threds");
    }

    @Test
    void unknownKeysCanUseThePublishedWarnCompatibilityMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MockPropertySource("commandLineArgs")
                .withProperty("agentworks.configuration.unknown-keys", "warn")
                .withProperty("agentworks.future.setting", "value"));

        validate(environment);
    }

    @Test
    void invalidTypesAndRemovedKeysFailWithActionableDiagnostics() {
        assertThatThrownBy(() -> validate(environment("agentworks.runs.worker.enabled", "sometimes")))
                .hasMessageContaining("must be true or false")
                .hasMessageNotContaining("NullPointerException");
        assertThatThrownBy(() -> validate(environment("agentworks.security.unsafe-local-access", "true")))
                .hasMessageContaining("removed AgentWorks configuration key")
                .hasMessageContaining("authenticated local profile");
    }

    @Test
    void zeroRunBudgetsAreValidAndNegativeBudgetsFailClosed() {
        for (String name : new String[]{"agentworks.runs.limits.max-total-tokens",
                "agentworks.runs.limits.max-model-calls", "agentworks.runs.limits.max-tool-calls",
                "agentworks.runs.limits.max-cost-usd"}) {
            validate(environment(name, "0"));
            assertThatThrownBy(() -> validate(environment(name, "-1")))
                    .hasMessageContaining(name)
                    .hasMessageContaining("must be non-negative");
        }
    }

    @Test
    void secretCanariesNeverAppearInValidationFailures() {
        String canary = "super-secret-canary-material";
        MockEnvironment environment = environment("agentworks.client.credential-ref", canary);
        environment.getPropertySources().addFirst(new MockPropertySource("commandLineArgs")
                .withProperty("agentworks.runs.worker.threads", "not-a-number"));

        assertThatThrownBy(() -> validate(environment))
                .hasMessageNotContaining(canary)
                .hasMessageContaining("agentworks.runs.worker.threads");
    }

    @Test
    void productionRejectsLocalEmbeddedInsecureAndPlaceholderSettings() {
        MockEnvironment local = environment("agentworks.development.local", "true");
        local.setActiveProfiles("production");
        assertThatThrownBy(() -> validate(local)).hasMessageContaining("forbids agentworks.development.local");

        MockEnvironment noTls = environment("agentworks.development.local", "false");
        noTls.setActiveProfiles("production");
        assertThatThrownBy(() -> validate(noTls)).hasMessageContaining("requires server TLS");

        MockEnvironment placeholder = productionEnvironment();
        placeholder.getPropertySources().addFirst(new MockPropertySource("commandLineArgs")
                .withProperty("agentworks.idempotency.hmac-keys.1", "change-me"));
        assertThatThrownBy(() -> validate(placeholder))
                .hasMessageContaining("rejects placeholder material")
                .hasMessageNotContaining("change-me");
    }

    @Test
    void productionAcceptsAProxyTerminatedPostgresqlProfile() {
        validate(productionEnvironment());
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = environment("agentworks.development.local", "false");
        environment.setActiveProfiles("production");
        environment.withProperty("spring.datasource.url", "jdbc:postgresql://database/agentworks");
        environment.withProperty("server.forward-headers-strategy", "framework");
        environment.withProperty("agentworks.security.session.cookie-secure", "true");
        return environment;
    }

    private static MockEnvironment environment(String name, String value) {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MockPropertySource("commandLineArgs").withProperty(name, value));
        return environment;
    }

    private void validate(MockEnvironment environment) {
        processor.postProcessEnvironment(environment, new SpringApplication(Object.class));
    }
}
