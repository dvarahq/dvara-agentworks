package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.CapabilityMode;
import com.dvarahq.agentworks.gateway.CapabilityPolicy;
import com.dvarahq.agentworks.gateway.ConfiguredGateway;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.GatewayEventSink;
import com.dvarahq.agentworks.gateway.GatewayRegistry;
import com.dvarahq.agentworks.gateway.LoggingGatewayEventSink;
import com.dvarahq.agentworks.gateway.ModelGateway;
import com.dvarahq.agentworks.gateway.fake.DeterministicFakeGatewayAdapter;
import com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayAdapter;
import com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Builds the gateway registry from {@code agentworks.gateway.gateways.<id>.*} plus any {@link ConfiguredGateway} beans,
 * and the {@link ModelGateway} that invokes release-pinned models. Every gateway is explicit: with none configured the
 * registry is empty and releases that declare model roles fail closed. Invalid configuration fails startup with a
 * {@link GatewayConfigurationException}.
 *
 * <p>The {@code deterministic-fake} type is permitted only under the local-development gate
 * ({@code agentworks.development.local=true} on a loopback-bound server, the {@code local} profile). Anywhere else, a fake gateway from properties, from a {@link ConfiguredGateway}
 * bean, or inside an application-supplied {@link GatewayRegistry} fails startup, and release resolution refuses it as
 * well (see {@code AgentWorksLifecycleAutoConfiguration}).
 *
 * <pre>
 * agentworks.gateway.gateways.direct.type=openai-compatible          # or deterministic-fake
 * agentworks.gateway.gateways.direct.base-url=https://api.openai.com/v1
 * agentworks.gateway.gateways.direct.credential-ref=openai-production  # resolved just in time; never a secret value
 * agentworks.gateway.gateways.direct.models[0].alias=coding-premium         # aliases are values: bound unchanged
 * agentworks.gateway.gateways.direct.models[0].model=gpt-4.1-2025-04-14
 * agentworks.gateway.gateways.direct.capabilities=STREAMING,USAGE,...  # optional restriction
 * agentworks.gateway.gateways.direct.capability-policy.streaming=REQUIRED
 * agentworks.gateway.gateways.direct.connect-timeout=10s
 * agentworks.gateway.gateways.direct.max-response-size=8MB        # blocking body; characters a stream may retain
 * agentworks.gateway.gateways.direct.max-stream-bytes=128MB        # optional wire bytes of one streamed response;
 *                                                                  # unset: max(64MB, 8 x max-response-size)
 * agentworks.gateway.gateways.direct.output-token-field=max-completion-tokens  # or max-tokens; default by host
 * </pre>
 */
@AutoConfiguration(before = AgentWorksLifecycleAutoConfiguration.class)
@ConditionalOnProperty(prefix = "agentworks.gateway", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class AgentWorksGatewayAutoConfiguration {

    static final String PREFIX = "agentworks.gateway.gateways";

    /**
     * Gateway ids are map keys, so they are restricted to characters every Spring property source (files, YAML, and
     * environment variables) binds unchanged. An AgentSpec naming any other id finds no gateway and fails with
     * {@code GATEWAY_NOT_CONFIGURED}.
     */
    static final Pattern GATEWAY_ID = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");

    /**
     * One catalog entry. The alias is a value, not a map key, so every character AgentSpec allows ({@code /}, {@code :},
     * {@code @}, {@code _}, {@code .}, and upper case) binds unchanged from any property source.
     */
    public record ModelEntry(String alias, String model) {
    }

    /** One configured gateway. The credential is a reference only; there is deliberately no field for a secret. */
    public record GatewayProperties(String type, URI baseUrl, String credentialRef, List<ModelEntry> models,
            Set<GatewayCapability> capabilities, Map<GatewayCapability, CapabilityMode> capabilityPolicy,
            Duration connectTimeout, DataSize maxResponseSize, DataSize maxStreamBytes,
            OpenAiCompatibleGatewayConfig.OutputTokenField outputTokenField) {
    }

    @Bean
    @ConditionalOnMissingBean
    GatewayRegistry agentWorksGatewayRegistry(Environment environment, ObjectProvider<ConfiguredGateway> contributed) {
        requireKnownFields(environment);
        Map<String, GatewayProperties> properties = Binder.get(environment)
                .bind(PREFIX, Bindable.mapOf(String.class, GatewayProperties.class)).orElse(Map.of());
        List<ConfiguredGateway> gateways = new ArrayList<>();
        boolean local = AgentWorksLocalDevelopment.enabled(environment);
        new TreeMap<>(properties).forEach((id, gateway) -> {
            ConfiguredGateway configured = configure(id, gateway);
            requirePermitted(configured, PREFIX + "." + id, local);
            gateways.add(configured);
        });
        contributed.orderedStream().forEach(gateway -> {
            requirePermitted(gateway, "ConfiguredGateway bean '" + gateway.gatewayId() + "'", local);
            gateways.add(gateway);
        });
        try {
            return GatewayRegistry.of(gateways);
        } catch (IllegalArgumentException duplicate) {
            throw new GatewayConfigurationException(PREFIX, duplicate.getMessage());
        }
    }

    /** {@link GatewayProperties} components, lower case without separators. */
    private static final Set<String> GATEWAY_FIELDS = Set.of("type", "baseurl", "credentialref", "models",
            "capabilities", "capabilitypolicy", "connecttimeout", "maxresponsesize", "maxstreambytes",
            "outputtokenfield");

    /**
     * Checks the bound structure (not raw property names) of every gateway entry:
     * <ul>
     *   <li>every field must be a {@link GatewayProperties} component, which also catches an id containing {@code .}
     *       ({@code gateways.openai.prod.type} binds gateway {@code openai} with field {@code prod});</li>
     *   <li>{@code models} must be a list: the old map form ({@code models.coding-premium=...}) would otherwise bind
     *       silently to an empty catalog.</li>
     * </ul>
     */
    static void requireKnownFields(Environment environment) {
        Map<String, Object> gateways = Binder.get(environment)
                .bind(PREFIX, Bindable.mapOf(String.class, Object.class)).orElse(Map.of());
        gateways.forEach((id, value) -> {
            String owner = PREFIX + "." + id;
            if (!(value instanceof Map<?, ?> fields)) {
                throw new GatewayConfigurationException(owner, "is not a gateway definition");
            }
            fields.forEach((field, fieldValue) -> {
                String uniform = requireKnownField(String.valueOf(field), owner);
                if (uniform.equals("models") && fieldValue instanceof Map<?, ?> entries
                        && !entries.keySet().stream().allMatch(key -> LIST_INDEX.matcher(String.valueOf(key)).matches())) {
                    throw new GatewayConfigurationException(owner, "models must be a list of alias and model entries, "
                            + "for example models[0].alias=coding-premium and models[0].model=gpt-4.1");
                }
            });
        });
    }

    /** A bound list element key: {@code 0}, or {@code [0]} from bracket notation. */
    private static final Pattern LIST_INDEX = Pattern.compile("^(\\d+|\\[\\d+])$");

    /**
     * Returns the whole key's uniform form (every element, lower case, no separators) when it names a
     * {@link GatewayProperties} component; otherwise the key, which is a property name and not a secret, is reported.
     */
    static String requireKnownField(String key, String owner) {
        ConfigurationPropertyName name;
        try {
            name = ConfigurationPropertyName.adapt(key, '.');
        } catch (RuntimeException invalid) {
            throw new GatewayConfigurationException(owner, "unusable gateway property key '" + key + "'");
        }
        if (name.isEmpty()) {
            throw new GatewayConfigurationException(owner, "unusable gateway property key '" + key + "'");
        }
        StringBuilder uniform = new StringBuilder();
        for (int element = 0; element < name.getNumberOfElements(); element++) {
            uniform.append(element == 0 ? "" : ".").append(name.getElement(element, ConfigurationPropertyName.Form.UNIFORM));
        }
        if (!GATEWAY_FIELDS.contains(uniform.toString())) {
            throw new GatewayConfigurationException(owner, "unknown gateway property '" + key
                    + "'; if it is part of the gateway id, ids may contain only a-z, 0-9, and '-'");
        }
        return uniform.toString();
    }

    /** The alias catalog from its entries; a missing alias or model, or a duplicate alias, fails startup. */
    static Map<String, String> catalog(List<ModelEntry> entries) {
        Map<String, String> catalog = new LinkedHashMap<>();
        if (entries == null) {
            return catalog;
        }
        for (int index = 0; index < entries.size(); index++) {
            ModelEntry entry = entries.get(index);
            if (entry == null || entry.alias() == null || entry.alias().isBlank() || entry.model() == null
                    || entry.model().isBlank()) {
                throw new IllegalArgumentException("models[" + index + "] needs both alias and model");
            }
            if (catalog.putIfAbsent(entry.alias(), entry.model()) != null) {
                throw new IllegalArgumentException("models[" + index + "] repeats an alias listed earlier");
            }
        }
        return catalog;
    }

    /** The deterministic fake never reaches a non-local installation, however it was wired. */
    static void requirePermitted(ConfiguredGateway gateway, String source, boolean local) {
        if (!local && DeterministicFakeGatewayAdapter.TYPE.equals(gateway.adapter().type())) {
            throw new GatewayConfigurationException(source, "gateway type " + DeterministicFakeGatewayAdapter.TYPE
                    + " is permitted only for local development: " + AgentWorksLocalDevelopment.requirement());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    GatewayEventSink agentWorksGatewayEventSink() {
        return new LoggingGatewayEventSink();
    }

    /**
     * A gateway with a {@code credential-ref} needs a unique {@link CredentialProvider} and {@link CredentialAuditSink};
     * without them startup fails rather than every invocation failing later with {@code CREDENTIAL_UNAVAILABLE}.
     */
    @Bean
    @ConditionalOnMissingBean
    ModelGateway agentWorksModelGateway(GatewayRegistry registry, ObjectProvider<CredentialProvider> credentials,
            ObjectProvider<CredentialAuditSink> audit, GatewayEventSink events, ObjectProvider<Clock> clocks,
            Environment environment) {
        // Also covers an application-supplied GatewayRegistry, which bypasses the registry bean's own validation.
        boolean local = AgentWorksLocalDevelopment.enabled(environment);
        registry.gateways().forEach(gateway ->
                requirePermitted(gateway, "GatewayRegistry gateway '" + gateway.gatewayId() + "'", local));
        CredentialProvider provider = credentials.getIfUnique();
        CredentialAuditSink auditSink = audit.getIfUnique();
        List<String> needing = registry.gateways().stream().filter(gateway -> gateway.credentialRef() != null)
                .map(gateway -> PREFIX + "." + gateway.gatewayId()).sorted().toList();
        if (!needing.isEmpty() && (provider == null || auditSink == null)) {
            throw new GatewayConfigurationException(String.join(", ", needing), "credential-ref is configured, but "
                    + "no unique CredentialProvider and CredentialAuditSink exist to resolve and audit it");
        }
        return new ModelGateway(registry, provider, auditSink, events, clocks.getIfUnique(Clock::systemUTC));
    }

    static ConfiguredGateway configure(String id, GatewayProperties gateway) {
        String owner = PREFIX + "." + id;
        try {
            if (!GATEWAY_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("gateway id must match " + GATEWAY_ID.pattern()
                        + " so that every property source binds it unchanged");
            }
            if (gateway.type() == null) {
                throw new IllegalArgumentException("type is required (gateway ids may contain only a-z, 0-9, and '-')");
            }
            Map<String, String> models = catalog(gateway.models());
            AiGatewayAdapter adapter = switch (gateway.type()) {
                case OpenAiCompatibleGatewayAdapter.TYPE -> {
                    if (gateway.baseUrl() == null) {
                        throw new IllegalArgumentException("base-url is required");
                    }
                    if (gateway.maxStreamBytes() != null && gateway.maxStreamBytes().toBytes() <= 0) {
                        // Checked here so that no configured value, not even -1B, can alias the UNSET sentinel.
                        throw new IllegalArgumentException("max-stream-bytes must be positive");
                    }
                    yield new OpenAiCompatibleGatewayAdapter(new OpenAiCompatibleGatewayConfig(id, gateway.baseUrl(),
                            models, gateway.capabilities(), gateway.connectTimeout(),
                            gateway.maxResponseSize() == null ? OpenAiCompatibleGatewayConfig.DEFAULT_MAX_RESPONSE_BYTES
                                    : gateway.maxResponseSize().toBytes(),
                            gateway.maxStreamBytes() == null ? OpenAiCompatibleGatewayConfig.UNSET
                                    : gateway.maxStreamBytes().toBytes(), gateway.outputTokenField()));
                }
                case DeterministicFakeGatewayAdapter.TYPE -> {
                    var builder = DeterministicFakeGatewayAdapter.builder(id).models(models);
                    if (gateway.capabilities() != null) {
                        builder.capabilities(gateway.capabilities());
                    }
                    yield builder.build();
                }
                default -> throw new IllegalArgumentException("unknown type; supported: "
                        + OpenAiCompatibleGatewayAdapter.TYPE + ", " + DeterministicFakeGatewayAdapter.TYPE
                        + ", or a ConfiguredGateway bean");
            };
            CapabilityPolicy policy = CapabilityPolicy.withOverrides(
                    gateway.capabilityPolicy() == null ? Map.of() : gateway.capabilityPolicy());
            CredentialRef credential = gateway.credentialRef() == null || gateway.credentialRef().isBlank()
                    ? null : new CredentialRef(gateway.credentialRef());
            return new ConfiguredGateway(adapter, policy, credential);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            // Validation messages name the rule broken, never the configured value.
            throw new GatewayConfigurationException(owner, propertyNames(String.valueOf(invalid.getMessage())));
        }
    }

    /**
     * The public configuration record names its own components ({@code maxStreamBytes}); an operator configures
     * properties ({@code max-stream-bytes}), so a startup failure names those instead (#174).
     */
    private static final Map<String, String> PROPERTY_NAMES = Map.of("baseUri", "base-url",
            "connectTimeout", "connect-timeout", "maxResponseBytes", "max-response-size",
            "maxStreamBytes", "max-stream-bytes", "outputTokenField", "output-token-field");

    static String propertyNames(String message) {
        String named = message;
        for (Map.Entry<String, String> component : PROPERTY_NAMES.entrySet()) {
            named = named.replaceAll("\\b" + component.getKey() + "\\b", component.getValue());
        }
        return named;
    }
}
