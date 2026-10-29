package com.dvarahq.agentworks.configuration;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** The machine-readable, versioned contract for every AgentWorks-owned server setting. */
final class AgentWorksConfigurationCatalog {
    static final String RESOURCE = "META-INF/agentworks/configuration-schema.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Entry(String name, String type, String defaultValue, String constraint, boolean sensitive, String owner,
            String reload, String since, String status, String replacement, String description, Pattern relaxedPattern) {
        Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(constraint, "constraint");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(reload, "reload");
            Objects.requireNonNull(since, "since");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(relaxedPattern, "relaxedPattern");
        }

        boolean matches(String propertyName) {
            return relaxedPattern.matcher(relaxed(propertyName)).matches();
        }
    }

    private final int schemaVersion;
    private final String unknownKeys;
    private final List<Entry> entries;

    private AgentWorksConfigurationCatalog(int schemaVersion, String unknownKeys, List<Entry> entries) {
        this.schemaVersion = schemaVersion;
        this.unknownKeys = unknownKeys;
        this.entries = entries;
    }

    static AgentWorksConfigurationCatalog load() {
        ClassPathResource resource = new ClassPathResource(RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            JsonNode root = JSON.readTree(input);
            int version = root.path("schemaVersion").asInt();
            if (version != 1) throw new IllegalStateException("unsupported AgentWorks configuration schema version");
            String unknown = required(root.path("compatibility"), "unknownKeys");
            List<Entry> entries = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonNode node : root.path("properties")) {
                String name = required(node, "name");
                if (!names.add(name)) throw new IllegalStateException("duplicate AgentWorks configuration rule: " + name);
                entries.add(new Entry(name, required(node, "type"), node.path("default").asString(""),
                        required(node, "constraint"), node.path("sensitive").asBoolean(), required(node, "owner"),
                        required(node, "reload"), required(node, "since"), required(node, "status"),
                        node.path("replacement").asString(""), required(node, "description"), pattern(name)));
            }
            if (entries.isEmpty()) throw new IllegalStateException("AgentWorks configuration schema is empty");
            return new AgentWorksConfigurationCatalog(version, unknown, List.copyOf(entries));
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read the AgentWorks configuration schema", exception);
        }
    }

    int schemaVersion() {
        return schemaVersion;
    }

    String unknownKeys() {
        return unknownKeys;
    }

    List<Entry> entries() {
        return entries;
    }

    Entry find(String propertyName) {
        String containerName = propertyName.replaceFirst("(?:\\[\\d+]|\\.\\d+)$", "");
        if (!containerName.equals(propertyName)) {
            Entry container = find(containerName);
            if (container != null && container.type().equals("string-list")) return container;
        }
        String relaxed = relaxed(propertyName);
        for (Entry candidate : entries) {
            if (!candidate.name().contains("<") && relaxed(candidate.name()).equals(relaxed)) return candidate;
        }
        Entry match = null;
        for (Entry candidate : entries) {
            if (!candidate.name().contains("<")) continue;
            if (!candidate.matches(propertyName)) continue;
            if (match != null) {
                throw new IllegalStateException("ambiguous AgentWorks configuration rules for " + propertyName);
            }
            match = candidate;
        }
        return match;
    }

    static String relaxed(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Pattern pattern(String configuredName) {
        StringBuilder regex = new StringBuilder("^");
        int position = 0;
        while (position < configuredName.length()) {
            int placeholder = configuredName.indexOf('<', position);
            if (placeholder < 0) {
                regex.append(Pattern.quote(relaxed(configuredName.substring(position))));
                break;
            }
            regex.append(Pattern.quote(relaxed(configuredName.substring(position, placeholder))));
            int end = configuredName.indexOf('>', placeholder);
            if (end < 0) throw new IllegalStateException("unclosed configuration placeholder: " + configuredName);
            regex.append("[a-z0-9]+");
            position = end + 1;
        }
        return Pattern.compile(regex.append('$').toString());
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asString("").strip();
        if (value.isEmpty()) throw new IllegalStateException("configuration schema entry is missing " + field);
        return value;
    }
}
