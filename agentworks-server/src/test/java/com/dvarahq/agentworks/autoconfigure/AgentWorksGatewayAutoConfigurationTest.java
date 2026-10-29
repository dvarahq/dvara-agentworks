package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.credential.CredentialAuditEvent;
import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.lifecycle.AgentReleaseId;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.gateway.CapabilityMode;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayEvent;
import com.dvarahq.agentworks.gateway.GatewayEventSink;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.InvocationCorrelation;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.ModelInvocation;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelRole;
import com.dvarahq.agentworks.gateway.ModelRoleBinding;
import com.dvarahq.agentworks.gateway.PinnedModelRoles;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Property-driven gateway wiring, fail-closed startup validation, contributed adapters, and JIT credentials. */
class AgentWorksGatewayAutoConfigurationTest {

    private static final String SECRET = "sk-autoconfig-never-logged";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentWorksGatewayAutoConfiguration.class));
    /** The loopback-only local-development gate that the server's local profile sets. */
    private final ApplicationContextRunner localRunner = runner.withPropertyValues(
            "agentworks.development.local=true", "server.address=127.0.0.1");

    @Test
    void withoutConfigurationTheRegistryIsEmptyAndModelRolesFailClosed() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ModelGateway.class);
            GatewayRegistry registry = context.getBean(GatewayRegistry.class);
            assertThat(registry.gateways()).isEmpty();
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(GatewayException.class, () -> registry
                    .resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium")))).code())
                    .isEqualTo(GatewayErrorCode.GATEWAY_NOT_CONFIGURED);
        });
    }

    @Test
    void propertiesConfigureDirectAndFakeGatewaysWithCapturedPolicy() {
        localRunner.withPropertyValues(
                "agentworks.gateway.gateways.direct.type=openai-compatible",
                "agentworks.gateway.gateways.direct.base-url=https://api.example.com/v1",
                "agentworks.gateway.gateways.direct.credential-ref=openai-production",
                "agentworks.gateway.gateways.direct.models[0].alias=coding-premium",
                "agentworks.gateway.gateways.direct.models[0].model=gpt-test-2026-09-01",
                "agentworks.gateway.gateways.direct.capabilities=STREAMING,USAGE,CANCELLATION",
                "agentworks.gateway.gateways.direct.capability-policy.streaming=REQUIRED",
                "agentworks.gateway.gateways.direct.capability-policy.tool-calls=DEGRADE",
                "agentworks.gateway.gateways.direct.max-response-size=1MB",
                "agentworks.gateway.gateways.local.type=deterministic-fake",
                "agentworks.gateway.gateways.local.models[0].alias=economical-small",
                "agentworks.gateway.gateways.local.models[0].model=fake-small")
                .withBean(CredentialProvider.class, AgentWorksGatewayAutoConfigurationTest::credentials)
                .withBean(CredentialAuditSink.class, () -> event -> { })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GatewayRegistry registry = context.getBean(GatewayRegistry.class);
                    ConfiguredGateway direct = registry.find("direct").orElseThrow();
                    assertThat(direct.adapter()).isInstanceOf(OpenAiCompatibleGatewayAdapter.class);
                    assertThat(direct.adapter().capabilities()).containsExactlyInAnyOrder(GatewayCapability.STREAMING,
                            GatewayCapability.USAGE, GatewayCapability.CANCELLATION);
                    assertThat(direct.credentialRef()).isEqualTo(new CredentialRef("openai-production"));
                    assertThat(direct.policy().modeFor(GatewayCapability.STREAMING)).isEqualTo(CapabilityMode.REQUIRED);
                    assertThat(direct.policy().modeFor(GatewayCapability.TOOL_CALLS)).isEqualTo(CapabilityMode.DEGRADE);
                    assertThat(direct.policy().modeFor(GatewayCapability.USAGE)).isEqualTo(CapabilityMode.DEGRADE);
                    assertThat(registry.find("local").orElseThrow().adapter())
                            .isInstanceOf(DeterministicFakeGatewayAdapter.class);
                    assertThat(registry.resolve(List.of(
                            new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"),
                            new ModelRoleBinding(ModelRole.COMPACTION, "local", "economical-small"))))
                            .hasSize(2);
                    assertThat(registry.toString()).doesNotContain("openai-production");
                });
    }

    @Test
    void invalidGatewayConfigurationFailsStartupWithTheOwningProperty() {
        for (String[] invalid : List.of(
                new String[]{"agentworks.gateway.gateways.direct.type=openai-compatible",
                        "agentworks.gateway.gateways.direct.base-url=http://api.example.com/v1"},
                new String[]{"agentworks.gateway.gateways.direct.type=openai-compatible",
                        "agentworks.gateway.gateways.direct.base-url=https://user:" + SECRET + "@api.example.com/v1"},
                new String[]{"agentworks.gateway.gateways.direct.type=openai-compatible"},
                new String[]{"agentworks.gateway.gateways.direct.type=langchain"},
                new String[]{"agentworks.gateway.gateways.direct.models[0].alias=m",
                        "agentworks.gateway.gateways.direct.models[0].model=x"},
                new String[]{"agentworks.gateway.gateways.direct.type=deterministic-fake",
                        "agentworks.gateway.gateways.direct.capability-policy.mcp-proxy=DEGRADE"},
                new String[]{"agentworks.gateway.gateways.Direct_Gateway.type=deterministic-fake"},
                new String[]{"agentworks.gateway.gateways.direct.type=deterministic-fake",
                        "agentworks.gateway.gateways.direct.credential-ref=needs-a-provider"})) {
            localRunner.withPropertyValues(invalid).run(context -> {
                assertThat(context).as(String.join(", ", invalid)).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().message().doesNotContain(SECRET);
                assertThat(stackMessages(context.getStartupFailure())).contains("agentworks.gateway.gateways.")
                        .doesNotContain(SECRET);
            });
        }
    }

    @Test
    void contributedGatewaysJoinTheRegistryAndDuplicateIdentifiersFailStartup() {
        ConfiguredGateway contributed = new ConfiguredGateway(DeterministicFakeGatewayAdapter.builder("dvarahq").build(),
                CapabilityPolicy.DEFAULT, null);
        localRunner.withBean(ConfiguredGateway.class, () -> contributed).run(context ->
                assertThat(context.getBean(GatewayRegistry.class).find("dvarahq")).contains(contributed));
        localRunner.withBean(ConfiguredGateway.class, () -> contributed)
                .withPropertyValues("agentworks.gateway.gateways.dvarahq.type=deterministic-fake")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theGatewayCanBeDisabled() {
        runner.withPropertyValues("agentworks.gateway.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(GatewayRegistry.class)
                        .doesNotHaveBean(ModelGateway.class));
    }

    @Test
    void theModelGatewayResolvesConfiguredCredentialsJustInTimeAndRecordsEvidence() {
        List<CredentialAuditEvent> audits = new CopyOnWriteArrayList<>();
        List<GatewayEvent> events = new CopyOnWriteArrayList<>();
        localRunner.withPropertyValues(
                        "agentworks.gateway.gateways.direct.type=deterministic-fake",
                        "agentworks.gateway.gateways.direct.credential-ref=fake-key",
                        "agentworks.gateway.gateways.direct.models[0].alias=coding-premium",
                        "agentworks.gateway.gateways.direct.models[0].model=fake-coding")
                .withBean(CredentialProvider.class, AgentWorksGatewayAutoConfigurationTest::credentials)
                .withBean(CredentialAuditSink.class, () -> audits::add)
                .withBean(GatewayEventSink.class, () -> events::add)
                .run(context -> {
                    WorkspaceId workspace = new WorkspaceId(UUID.randomUUID());
                    var pins = PinnedModelRoles.of(workspace, new AgentReleaseId(UUID.randomUUID()),
                            context.getBean(GatewayRegistry.class).resolve(List.of(
                                    new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"))));
                    var identity = new InvocationIdentity(UUID.randomUUID(), workspace.value(), UUID.randomUUID(),
                            UUID.randomUUID(), UUID.randomUUID());

                    var response = context.getBean(ModelGateway.class).call(new ModelInvocation(
                            pins.role(ModelRole.REASONING), identity, new InvocationCorrelation(null, "trace"),
                            ModelRequest.of(ModelMessage.user("hello")), Duration.ofSeconds(5), null));

                    assertThat(response.content()).isEqualTo("echo(fake-coding): hello");
                    var fake = (DeterministicFakeGatewayAdapter) context.getBean(GatewayRegistry.class)
                            .find("direct").orElseThrow().adapter();
                    assertThat(fake.lastCredentialDigest())
                            .isEqualTo(DeterministicFakeGatewayAdapter.digest(SECRET.toCharArray()));
                    assertThat(audits).singleElement().satisfies(audit ->
                            assertThat(audit.outcome()).isEqualTo("SUCCEEDED"));
                    assertThat(events).hasSize(2);
                    assertThat(events.get(0)).isInstanceOf(GatewayEvent.ModelInvocationAttempted.class);
                    assertThat(events.get(1)).isInstanceOf(GatewayEvent.ModelInvocationCompleted.class);
                    assertThat(events.toString() + audits).doesNotContain(SECRET);
                });
    }

    @Test
    void theFakeGatewayIsPermittedOnlyUnderTheLocalDevelopmentGate() {
        String[] fake = {"agentworks.gateway.gateways.direct.type=deterministic-fake",
                "agentworks.gateway.gateways.direct.credential-ref=" + SECRET,
                "agentworks.gateway.gateways.direct.models[0].alias=coding-premium",
                "agentworks.gateway.gateways.direct.models[0].model=" + SECRET};
        localRunner.withPropertyValues(fake)
                .withBean(CredentialProvider.class, AgentWorksGatewayAutoConfigurationTest::credentials)
                .withBean(CredentialAuditSink.class, () -> event -> { })
                .run(context -> assertThat(context.getBean(GatewayRegistry.class).find("direct")).isPresent());
        for (String[] notLocal : List.of(new String[0],
                new String[]{"agentworks.development.local=false", "server.address=127.0.0.1"},
                // The removed #155 setting grants nothing.
                new String[]{"agentworks.security.unsafe-local-access=true", "agentworks.tenancy.bootstrap-default=true"},
                new String[]{"agentworks.tenancy.bootstrap-default=true"})) {
            runner.withPropertyValues(notLocal).withPropertyValues(fake).run(context -> {
                assertThat(context).as(String.join(", ", notLocal)).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOfSatisfying(GatewayConfigurationException.class, failure -> {
                            assertThat(failure.source()).isEqualTo("agentworks.gateway.gateways.direct");
                            assertThat(failure.getMessage()).contains("deterministic-fake")
                                    .contains("local development");
                        });
                assertThat(stackMessages(context.getStartupFailure())).doesNotContain(SECRET);
            });
        }
    }

    @Test
    void theLocalDevelopmentFlagWithoutLoopbackBindingFailsStartup() {
        for (String[] reachable : List.of(new String[]{"agentworks.development.local=true"},
                new String[]{"agentworks.development.local=true", "server.address=0.0.0.0"},
                new String[]{"agentworks.development.local=true", "server.address=203.0.113.10"},
                new String[]{"agentworks.development.local=true", "server.address=::"})) {
            runner.withPropertyValues(reachable).run(context -> {
                assertThat(context).as(String.join(", ", reachable)).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("agentworks.development.local=true requires server.address");
            });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> AgentWorksLifecycleAutoConfiguration
                            .releaseResolver(env(reachable), GatewayRegistry.empty()))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String address : List.of("127.0.0.1", "::1", "localhost")) {
            runner.withPropertyValues("agentworks.development.local=true", "server.address=" + address)
                    .run(context -> assertThat(context).as(address).hasNotFailed());
        }
    }

    private static MockEnvironment env(String[] properties) {
        MockEnvironment environment = new MockEnvironment();
        for (String property : properties) {
            int split = property.indexOf('=');
            environment.setProperty(property.substring(0, split), property.substring(split + 1));
        }
        return environment;
    }

    @Test
    void contributedOrApplicationSuppliedFakesFailOutsideLocalDevelopment() {
        ConfiguredGateway fake = new ConfiguredGateway(DeterministicFakeGatewayAdapter.builder("direct").build(),
                CapabilityPolicy.DEFAULT, null);

        runner.withBean(ConfiguredGateway.class, () -> fake).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(GatewayConfigurationException.class)
                    .hasMessageContaining("ConfiguredGateway bean 'direct'");
        });
        runner.withBean(GatewayRegistry.class, () -> GatewayRegistry.of(List.of(fake))).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(GatewayConfigurationException.class)
                    .hasMessageContaining("GatewayRegistry gateway 'direct'");
        });
    }

    @Test
    void releaseResolutionRefusesTheFakeOutsideLocalDevelopment() {
        GatewayRegistry registry = GatewayRegistry.of(List.of(
                new ConfiguredGateway(DeterministicFakeGatewayAdapter.builder("direct").model("coding-premium", "fake")
                        .build(), CapabilityPolicy.DEFAULT, null),
                new ConfiguredGateway(new OpenAiCompatibleGatewayAdapter(
                        com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayConfig.of("remote",
                                java.net.URI.create("https://api.example.com/v1"), java.util.Map.of("m", "model-1"))),
                        CapabilityPolicy.DEFAULT, null)));
        var fakeRole = List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", "coding-premium"));
        var remoteRole = List.of(new ModelRoleBinding(ModelRole.REASONING, "remote", "m"));
        var production = AgentWorksLifecycleAutoConfiguration.releaseResolver(new MockEnvironment(), registry);
        var local = AgentWorksLifecycleAutoConfiguration.releaseResolver(new MockEnvironment()
                .withProperty("agentworks.development.local", "true")
                .withProperty("server.address", "127.0.0.1"), registry);

        var refused = org.assertj.core.api.Assertions.catchThrowableOfType(GatewayException.class,
                () -> production.resolve(fakeRole));

        assertThat(refused.code()).isEqualTo(GatewayErrorCode.GATEWAY_NOT_PERMITTED);
        assertThat(refused.getMessage()).contains("reasoning").contains("local development");
        assertThat(production.resolve(remoteRole).get(ModelRole.REASONING).providerModel()).isEqualTo("model-1");
        assertThat(local.resolve(fakeRole).get(ModelRole.REASONING).providerModel()).isEqualTo("fake");
    }

    // Review 3, finding 1: aliases are values and bind unchanged from every source; gateway ids are conservative keys.
    @Test
    void aliasesBindAsValuesFromEverySourceAndGatewayIdsAreConservative() {
        java.util.Map<String, Object> file = new java.util.LinkedHashMap<>();
        file.put("agentworks.gateway.gateways.direct.type", "deterministic-fake");
        String[] aliases = {"openai/gpt-4o", "coding_premium", "meta/llama:70b@v1", "Coding-Premium", "gpt-4.1"};
        for (int index = 0; index < aliases.length; index++) {
            file.put("agentworks.gateway.gateways.direct.models[" + index + "].alias", aliases[index]);
            file.put("agentworks.gateway.gateways.direct.models[" + index + "].model", "model-" + index);
        }
        java.util.Map<String, Object> environment = java.util.Map.of(
                "AGENTWORKS_GATEWAY_GATEWAYS_ENVGW_TYPE", "deterministic-fake",
                "AGENTWORKS_GATEWAY_GATEWAYS_ENVGW_MODELS_0_ALIAS", "openai/gpt-4o:mini@v1_A",
                "AGENTWORKS_GATEWAY_GATEWAYS_ENVGW_MODELS_0_MODEL", "env-model");

        localRunner.withInitializer(context -> {
            var sources = context.getEnvironment().getPropertySources();
            sources.addFirst(new org.springframework.core.env.MapPropertySource("file", file));
            sources.addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
                    "test-" + org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    environment));
        }).run(context -> {
            assertThat(context).hasNotFailed();
            GatewayRegistry registry = context.getBean(GatewayRegistry.class);
            for (int index = 0; index < aliases.length; index++) {
                assertThat(registry.resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "direct", aliases[index])))
                        .get(ModelRole.REASONING).providerModel()).as(aliases[index]).isEqualTo("model-" + index);
            }
            assertThat(registry.resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "envgw",
                    "openai/gpt-4o:mini@v1_A"))).get(ModelRole.REASONING).providerModel()).isEqualTo("env-model");
            // An AgentSpec id that no configuration can express finds no gateway.
            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(GatewayException.class, () -> registry
                    .resolve(List.of(new ModelRoleBinding(ModelRole.REASONING, "openai.prod", "openai/gpt-4o")))).code())
                    .isEqualTo(GatewayErrorCode.GATEWAY_NOT_CONFIGURED);
        });

        for (String[] invalid : List.of(
                new String[]{"agentworks.gateway.gateways.direct.type=deterministic-fake",
                        "agentworks.gateway.gateways.direct.models[0].alias=same",
                        "agentworks.gateway.gateways.direct.models[0].model=a",
                        "agentworks.gateway.gateways.direct.models[1].alias=same",
                        "agentworks.gateway.gateways.direct.models[1].model=b"},
                new String[]{"agentworks.gateway.gateways.direct.type=deterministic-fake",
                        "agentworks.gateway.gateways.direct.models[0].alias=no-model"},
                new String[]{"agentworks.gateway.gateways[openai.prod].type=deterministic-fake"},
                new String[]{"agentworks.gateway.gateways[my_gateway].type=deterministic-fake"},
                new String[]{"agentworks.gateway.gateways[Direct].type=deterministic-fake"},
                new String[]{"agentworks.gateway.gateways.openai.prod.type=deterministic-fake"})) {
            localRunner.withPropertyValues(invalid).run(context -> {
                assertThat(context).as(String.join(", ", invalid)).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().isInstanceOf(GatewayConfigurationException.class);
            });
        }
    }

    // Review 4, item 2: the old map-style models never bind silently to an empty catalog, from any source.
    @Test
    void mapStyleModelsFailStartupFromFilesYamlAndEnvironmentVariables() throws Exception {
        String mapYaml = """
                agentworks:
                  gateway:
                    gateways:
                      direct:
                        type: deterministic-fake
                        models:
                          coding-premium: gpt-4.1
                """;
        String listYaml = """
                agentworks:
                  gateway:
                    gateways:
                      direct:
                        type: deterministic-fake
                        models:
                          - alias: "openai/gpt-4o:mini"
                            model: gpt-4o-mini
                """;
        var yamlLoader = new org.springframework.boot.env.YamlPropertySourceLoader();
        var mapSource = yamlLoader.load("map-yaml", new org.springframework.core.io.ByteArrayResource(
                mapYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getFirst();
        var listSource = yamlLoader.load("list-yaml", new org.springframework.core.io.ByteArrayResource(
                listYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))).getFirst();
        var environment = new org.springframework.core.env.SystemEnvironmentPropertySource(
                "test-" + org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                java.util.Map.of("AGENTWORKS_GATEWAY_GATEWAYS_DIRECT_TYPE", "deterministic-fake",
                        "AGENTWORKS_GATEWAY_GATEWAYS_DIRECT_MODELS_CODINGPREMIUM", "gpt-4.1"));

        for (org.springframework.core.env.PropertySource<?> mapStyle : List.of(mapSource, environment,
                new org.springframework.core.env.MapPropertySource("file", java.util.Map.of(
                        "agentworks.gateway.gateways.direct.type", "deterministic-fake",
                        "agentworks.gateway.gateways.direct.models.coding-premium", "gpt-4.1")))) {
            localRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(mapStyle))
                    .run(context -> {
                        assertThat(context).as(mapStyle.getName()).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(GatewayConfigurationException.class)
                                .hasMessageContaining("models[0].alias");
                    });
        }
        localRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(listSource))
                .run(context -> assertThat(context.getBean(GatewayRegistry.class).resolve(List.of(new ModelRoleBinding(
                        ModelRole.REASONING, "direct", "openai/gpt-4o:mini"))).get(ModelRole.REASONING).providerModel())
                        .isEqualTo("gpt-4o-mini"));
    }

    // Review 4, item 9: an unknown gateway field is named in the error.
    @Test
    void unknownGatewayFieldsAreNamed() {
        localRunner.withPropertyValues("agentworks.gateway.gateways.direct.type=deterministic-fake",
                        "agentworks.gateway.gateways.direct.base-uri=https://api.example.com/v1")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(GatewayConfigurationException.class).hasMessageContaining("'base-uri'"));
    }

    // Review 4, item 10: one documented id rule is the rule enforced; a trailing '-' is refused by it.
    @Test
    void configuredGatewayIdsFollowTheDocumentedRule() {
        localRunner.withPropertyValues("agentworks.gateway.gateways[direct-].type=deterministic-fake")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(GatewayConfigurationException.class)
                        .hasMessageContaining("gateway id must match " + AgentWorksGatewayAutoConfiguration.GATEWAY_ID));
        localRunner.withPropertyValues("agentworks.gateway.gateways.a1-b2.type=deterministic-fake")
                .run(context -> assertThat(context.getBean(GatewayRegistry.class).find("a1-b2")).isPresent());
    }

    // Review 4, item 7: startup refuses a stream cap below the content bound.
    @Test
    void aStreamCapBelowTheContentBoundFailsStartup() {
        localRunner.withPropertyValues("agentworks.gateway.gateways.direct.type=openai-compatible",
                        "agentworks.gateway.gateways.direct.base-url=https://api.example.com/v1",
                        "agentworks.gateway.gateways.direct.max-response-size=16MB",
                        "agentworks.gateway.gateways.direct.max-stream-bytes=8MB")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(GatewayConfigurationException.class).hasMessageContaining("max-stream-bytes")
                        .hasMessageContaining("max-response-size"));
    }

    // #174 medium 1: max-stream-bytes=0B (or any non-positive size) fails startup instead of meaning "unset".
    @Test
    void aZeroOrNegativeStreamCapFailsStartup() {
        for (String size : List.of("0B", "-1B")) {
            localRunner.withPropertyValues("agentworks.gateway.gateways.direct.type=openai-compatible",
                            "agentworks.gateway.gateways.direct.base-url=https://api.example.com/v1",
                            "agentworks.gateway.gateways.direct.max-stream-bytes=" + size)
                    .run(context -> assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(GatewayConfigurationException.class)
                            .hasMessageContaining("max-stream-bytes must be positive"));
        }
    }

    // Review 5, item 4: raising max-response-size alone never fails startup; the stream cap follows it.
    @Test
    void raisingOnlyTheContentBoundStartsWithADerivedStreamCap() {
        localRunner.withPropertyValues("agentworks.gateway.gateways.direct.type=openai-compatible",
                        "agentworks.gateway.gateways.direct.base-url=https://api.example.com/v1",
                        "agentworks.gateway.gateways.direct.max-response-size=100MB")
                .run(context -> assertThat(context).hasNotFailed());
    }

    // Review 5, item 6: a field key is judged by its whole name, and an unusable key is a configuration error.
    @Test
    void fieldKeysAreJudgedByTheirWholeName() {
        localRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("file", java.util.Map.of(
                                "agentworks.gateway.gateways.direct.type", "deterministic-fake",
                                "agentworks.gateway.gateways.direct[type.extra]", "x"))))
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(GatewayConfigurationException.class).hasMessageContaining("type.extra"));
    }

    // #174 low 1: any holder of the public configuration, not only the adapter, can run its cross-field validation.
    @Test
    void thePublicConfigurationValidatesItsCrossFieldInvariantForAnyHolder() {
        var inconsistent = com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayConfig
                .of("direct", java.net.URI.create("https://api.example.com/v1"), Map.of())
                .withMaxResponseBytes(8_192).withMaxStreamBytes(4_096);
        assertThatThrownBy(inconsistent::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxStreamBytes must be at least maxResponseBytes");
        var consistent = inconsistent.withMaxStreamBytes(8_192);
        assertThat(consistent.validate()).isSameAs(consistent);
    }

    // #174 low 5: the public record names its components; startup failures name the operator's properties instead.
    @Test
    void startupFailuresNameGatewayPropertiesNotRecordComponents() {
        localRunner.withPropertyValues("agentworks.gateway.gateways.direct.type=openai-compatible",
                        "agentworks.gateway.gateways.direct.base-url=https://api.example.com/v1",
                        "agentworks.gateway.gateways.direct.connect-timeout=0s")
                .run(context -> assertThat(context.getStartupFailure()).rootCause()
                        .isInstanceOf(GatewayConfigurationException.class)
                        .hasMessageContaining("connect-timeout must be positive")
                        .message().doesNotContain("connectTimeout"));
        assertThat(AgentWorksGatewayAutoConfiguration.propertyNames(
                "maxStreamBytes must be at least maxResponseBytes"))
                .isEqualTo("max-stream-bytes must be at least max-response-size");
    }

    // #174 low 7 (refuted): every relaxed spelling of a field that passes the unknown-field guard binds that field.
    // A stream cap of 1B, set through each spelling, reaches the cross-field check and fails startup.
    @Test
    void everyRelaxedFieldSpellingThatPassesTheGuardBindsItsField() {
        for (String key : List.of("max-stream-bytes", "maxStreamBytes", "max_stream_bytes", "Max_Stream_Bytes",
                "MAX_STREAM_BYTES", "maxstreambytes", "max-Stream-bytes")) {
            localRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("file", Map.of(
                                    "agentworks.gateway.gateways.direct.type", "openai-compatible",
                                    "agentworks.gateway.gateways.direct.base-url", "https://api.example.com/v1",
                                    "agentworks.gateway.gateways.direct." + key, "1B"))))
                    .run(context -> assertThat(context.getStartupFailure()).as(key).rootCause()
                            .isInstanceOf(GatewayConfigurationException.class)
                            .hasMessageContaining("max-stream-bytes must be at least max-response-size"));
        }
    }

    @Test
    void anUnusableFieldKeyIsAConfigurationError() {
        assertThatThrownBy(() -> AgentWorksGatewayAutoConfiguration.requireKnownField("", "agentworks.gateway.gateways.x"))
                .isInstanceOf(GatewayConfigurationException.class);
    }

    private static CredentialProvider credentials() {
        return new CredentialProvider() {
            @Override
            public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
                return new CredentialAuthorization(UUID.randomUUID(), reference, identity);
            }

            @Override
            public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
                return new ResolvedCredential(SECRET.toCharArray(), now.plusSeconds(60));
            }
        };
    }

    private static String stackMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
