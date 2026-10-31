package com.dvarahq.agentworks.configuration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.unit.DataSize;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates AgentWorks-owned configuration before the application context creates a database, worker, exposure, or
 * sandbox bean. It deliberately never resolves credential references or renders sensitive values.
 */
public final class AgentWorksConfigurationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final System.Logger LOG = System.getLogger(AgentWorksConfigurationEnvironmentPostProcessor.class.getName());
    private static final Set<String> PLACEHOLDERS = Set.of("change-me", "changeme", "password", "secret", "example");

    @Override
    public int getOrder() {
        // ConfigData must already be available, while validation still precedes context creation and Flyway.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        AgentWorksConfigurationCatalog catalog = AgentWorksConfigurationCatalog.load();
        Map<String, ConfiguredValue> configured = configuredValues(environment);
        String unknownPolicy = environment.getProperty("agentworks.configuration.unknown-keys", catalog.unknownKeys());
        boolean diagnostics = environment.getProperty("agentworks.configuration.diagnostics", Boolean.class, false);
        List<String> warnings = new ArrayList<>();

        for (ConfiguredValue configuredValue : configured.values()) {
            AgentWorksConfigurationCatalog.Entry entry = catalog.find(configuredValue.name());
            if (entry == null) {
                String message = "unknown AgentWorks configuration key '" + configuredValue.name()
                        + "' (origin=" + configuredValue.origin() + ")";
                if ("warn".equalsIgnoreCase(unknownPolicy)) warnings.add(message);
                else throw invalid(message);
                continue;
            }
            if ("removed".equals(entry.status())) {
                throw invalid("removed AgentWorks configuration key '" + configuredValue.name() + "'; use "
                        + entry.replacement());
            }
            validate(entry, configuredValue.value(), configuredValue.name());
            if ("deprecated".equals(entry.status())) {
                warnings.add("deprecated AgentWorks configuration key '" + configuredValue.name() + "'; use "
                        + entry.replacement());
            }
            if (diagnostics) {
                String rendered = entry.sensitive() ? "<redacted>" : safeValue(entry, configuredValue.value());
                LOG.log(System.Logger.Level.INFO, "AgentWorks configuration {0}={1} origin={2}",
                        configuredValue.name(), rendered, configuredValue.origin());
            }
        }
        warnings.forEach(message -> LOG.log(System.Logger.Level.WARNING, message));
        validateProduction(environment, configured, catalog);
        LOG.log(System.Logger.Level.DEBUG, "AgentWorks configuration schema v{0} validated", catalog.schemaVersion());
    }

    private static Map<String, ConfiguredValue> configuredValues(ConfigurableEnvironment environment) {
        Map<String, ConfiguredValue> result = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String rawName : enumerable.getPropertyNames()) {
                String name = canonicalAgentWorksName(rawName);
                if (name == null) continue;
                String identity = AgentWorksConfigurationCatalog.relaxed(name);
                if (result.containsKey(identity)) continue; // Property sources are ordered from highest precedence.
                Object raw = source.getProperty(rawName);
                if (raw != null) result.put(identity, new ConfiguredValue(name, String.valueOf(raw), origin(source)));
            }
        }
        return result;
    }

    private static String canonicalAgentWorksName(String rawName) {
        if (rawName.startsWith("agentworks.")) return rawName.toLowerCase(Locale.ROOT);
        if (!rawName.startsWith("AGENTWORKS_")) return null;
        return rawName.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static String origin(PropertySource<?> source) {
        String name = source.getName().toLowerCase(Locale.ROOT);
        if (name.contains("commandline")) return "command-line";
        if (name.contains("systemproperties")) return "system-property";
        if (name.contains("systemenvironment")) return "environment-variable";
        if (name.contains("config resource") || name.contains("applicationconfig")) return "configuration-file";
        if (name.contains("defaultproperties")) return "profile-default";
        return "application";
    }

    private static void validate(AgentWorksConfigurationCatalog.Entry entry, String raw, String name) {
        try {
            switch (entry.type()) {
                case "boolean" -> {
                    if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) fail(name, "must be true or false");
                }
                case "integer" -> validateInteger(Integer.parseInt(raw.strip()), entry.constraint(), name);
                case "long" -> validateLong(Long.parseLong(raw.strip()), entry.constraint(), name);
                case "decimal" -> {
                    java.math.BigDecimal value = new java.math.BigDecimal(raw.strip());
                    if (entry.constraint().equals("positive") && value.signum() <= 0) fail(name, "must be positive");
                    if (entry.constraint().equals("non-negative") && value.signum() < 0) {
                        fail(name, "must be non-negative");
                    }
                }
                case "duration" -> positive(DurationStyle.detectAndParse(raw), name);
                case "data-size" -> {
                    if (DataSize.parse(raw).toBytes() <= 0) fail(name, "must be positive");
                }
                case "uri" -> validateUri(URI.create(raw.strip()), entry.constraint(), name);
                case "path" -> {
                    if (raw.isBlank()) fail(name, "must not be blank");
                    Path.of(raw);
                }
                case "credential-ref", "secret", "string" -> {
                    if (entry.constraint().contains("non-empty") && raw.isBlank()) fail(name, "must not be blank");
                }
                case "enum" -> validateEnum(raw, entry.constraint(), name);
                case "string-list", "string-map" -> { /* Spring performs structural binding. */ }
                default -> fail(name, "has an unsupported catalogue type");
            }
        } catch (IllegalArgumentException exception) {
            if (exception instanceof ConfigurationFailure) throw exception;
            throw invalid("invalid AgentWorks configuration key '" + name + "': " + safeReason(exception));
        }
    }

    private static void validateInteger(int value, String constraint, String name) {
        validateLong(value, constraint, name);
    }

    private static void validateLong(long value, String constraint, String name) {
        if (constraint.equals("positive") && value <= 0) fail(name, "must be positive");
        if (constraint.equals("non-negative") && value < 0) fail(name, "must be non-negative");
        if (constraint.matches("\\d+\\.\\.\\d+")) {
            String[] bounds = constraint.split("\\.\\.");
            if (value < Long.parseLong(bounds[0]) || value > Long.parseLong(bounds[1])) {
                fail(name, "must be between " + bounds[0] + " and " + bounds[1]);
            }
        }
    }

    private static void validateUri(URI uri, String constraint, String name) {
        if (!uri.isAbsolute()) fail(name, "must be an absolute URI");
        if (constraint.contains("http or https")
                && !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            fail(name, "must use http or https");
        }
    }

    private static void validateEnum(String raw, String constraint, String name) {
        if (!constraint.contains("|")) return;
        for (String allowed : constraint.split("\\|")) {
            if (allowed.equalsIgnoreCase(raw.strip())) return;
        }
        fail(name, "must be one of " + constraint);
    }

    private static void validateProduction(ConfigurableEnvironment environment,
            Map<String, ConfiguredValue> configured, AgentWorksConfigurationCatalog catalog) {
        boolean production = List.of(environment.getActiveProfiles()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> value.equals("prod") || value.equals("production"));
        if (!production) return;
        if (environment.getProperty("agentworks.development.local", Boolean.class, false)) {
            throw invalid("production profile forbids agentworks.development.local");
        }
        if (environment.getProperty("agentworks.compatibility.allow-embedded-database", Boolean.class, false)) {
            throw invalid("production profile forbids the embedded database");
        }
        String dataSource = environment.getProperty("spring.datasource.url", "");
        if (dataSource.startsWith("jdbc:h2:")) throw invalid("production profile requires PostgreSQL");
        boolean tls = environment.getProperty("server.ssl.enabled", Boolean.class, false);
        String forwarding = environment.getProperty("server.forward-headers-strategy", "none");
        if (!tls && "none".equalsIgnoreCase(forwarding)) {
            throw invalid("production profile requires server TLS or an explicit trusted-proxy forwarding strategy");
        }
        if (!environment.getProperty("agentworks.security.session.cookie-secure", Boolean.class, true)) {
            throw invalid("production profile requires secure session cookies");
        }
        for (ConfiguredValue value : configured.values()) {
            AgentWorksConfigurationCatalog.Entry entry = catalog.find(value.name());
            if (entry != null && entry.sensitive() && PLACEHOLDERS.contains(value.value().strip().toLowerCase(Locale.ROOT))) {
                throw invalid("production profile rejects placeholder material for '" + value.name() + "'");
            }
        }
    }

    private static String safeValue(AgentWorksConfigurationCatalog.Entry entry, String value) {
        if (entry.type().equals("path") || entry.type().equals("credential-ref") || entry.type().equals("secret")) {
            return "<redacted>";
        }
        String stripped = value.strip();
        return stripped.length() <= 128 ? stripped : stripped.substring(0, 125) + "...";
    }

    private static String safeReason(IllegalArgumentException exception) {
        if (exception instanceof ConfigurationFailure failure) return failure.getMessage();
        return "value does not satisfy type " + exception.getClass().getSimpleName().replace("Exception", "");
    }

    private static void positive(Duration value, String name) {
        if (value.isZero() || value.isNegative()) fail(name, "must be positive");
    }

    private static void fail(String name, String reason) {
        throw new ConfigurationFailure("'" + name + "' " + reason);
    }

    private static ConfigurationFailure invalid(String message) {
        return new ConfigurationFailure(message);
    }

    private record ConfiguredValue(String name, String value, String origin) {}

    private static final class ConfigurationFailure extends IllegalArgumentException {
        private ConfigurationFailure(String message) { super(message); }
    }
}
