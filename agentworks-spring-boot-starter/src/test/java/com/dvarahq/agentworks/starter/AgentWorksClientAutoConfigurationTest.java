package com.dvarahq.agentworks.starter;

import com.dvarahq.agentworks.sdk.AgentWorksClient;
import com.dvarahq.agentworks.sdk.ApiCall;
import com.dvarahq.agentworks.sdk.ApiOperation;
import com.dvarahq.agentworks.sdk.extension.AgentWorksExtension;
import com.dvarahq.agentworks.sdk.extension.ExtensionRegistry;
import com.dvarahq.agentworks.sdk.extension.ProviderKind;
import com.dvarahq.agentworks.sdk.extension.ProviderRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentWorksClientAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentWorksClientAutoConfiguration.class));

    @Test void disabledByDefaultAndPerformsNoHiddenSetup() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AgentWorksClient.class)
                .doesNotHaveBean(ExtensionRegistry.class));
    }

    @Test void optInStartsWithoutCredentialOrExternalServiceAndReportsSafeHealth() {
        runner.withPropertyValues("agentworks.client.enabled=true", "agentworks.client.base-url=http://127.0.0.1:9")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AgentWorksClient.class)
                            .hasSingleBean(ExtensionRegistry.class);
                    HealthIndicator health = context.getBean("agentWorksClientHealthIndicator", HealthIndicator.class);
                    assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
                    assertThat(health.health().getDetails()).containsEntry("connectivity", "NOT_PROBED")
                            .containsEntry("credentialReferenceConfigured", false);
                });
    }

    @Test void userClientBacksOffAndSpringExtensionsJoinTheRegistry() {
        AgentWorksClient user = AgentWorksClient.builder(URI.create("http://localhost:8080")).build();
        Runnable provider = () -> { };
        AgentWorksExtension extension = () -> List.of(new ProviderRegistration<>(ProviderKind.TOOL, "spring-tool",
                Runnable.class, provider, Set.of("bounded")));
        runner.withPropertyValues("agentworks.client.enabled=true")
                .withBean(AgentWorksClient.class, () -> user)
                .withBean(AgentWorksExtension.class, () -> extension)
                .run(context -> {
                    assertThat(context.getBean(AgentWorksClient.class)).isSameAs(user);
                    assertThat(context.getBean(ExtensionRegistry.class).providers(ProviderKind.TOOL))
                            .extracting(ProviderRegistration::id).containsExactly("spring-tool");
                });
    }

    @Test void credentialReferenceIsNotResolvedDuringStartup() {
        int[] calls = {0};
        runner.withPropertyValues("agentworks.client.enabled=true", "agentworks.client.credential-ref=vault://sdk/token")
                .withBean(CredentialReferenceResolver.class, () -> reference -> { calls[0]++; return "token".toCharArray(); })
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AgentWorksClient.class);
                    assertThat(calls[0]).isZero();
                });
    }

    @Test void configuredCredentialReferenceWithoutResolverFailsBeforeNetworkAccess() {
        runner.withPropertyValues("agentworks.client.enabled=true", "agentworks.client.base-url=http://127.0.0.1:9",
                        "agentworks.client.credential-ref=vault://sdk/token")
                .run(context -> assertThatThrownBy(() -> context.getBean(AgentWorksClient.class).execute(
                        ApiCall.operation(ApiOperation.GET_RUN).path("id", "run-1").build()))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("credential resolver unavailable for configured reference"));
    }
}
