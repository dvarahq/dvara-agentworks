package com.dvarahq.agentworks.runplane.exposure;

import com.dvarahq.agentworks.exposure.ExposureDescriptorGenerator;
import com.dvarahq.agentworks.exposure.ExposureSpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic A2A/MCP/channel descriptor derived only from authoritative release and deployment data. */
public final class CanonicalExposureDescriptorGenerator implements ExposureDescriptorGenerator {
    private final JsonMapper json = new JsonMapper();

    @Override
    public Map<String, Object> generate(ExposureSpec exposure, String releaseManifestJson, String inputSchema,
            String outputSchema) {
        Objects.requireNonNull(exposure, "exposure");
        Objects.requireNonNull(releaseManifestJson, "releaseManifestJson");
        try {
            JsonNode manifest = json.readTree(releaseManifestJson);
            if (manifest == null || !manifest.isObject() || !manifest.path("manifestVersion").isInt()
                    || !manifest.path("agent").isObject()) throw new IllegalArgumentException("invalid release manifest");
            JsonNode agent = manifest.path("agent");
            Map<String, Object> descriptor = new TreeMap<>();
            descriptor.put("descriptorVersion", "exposure/v1");
            descriptor.put("exposureId", exposure.id());
            descriptor.put("exposureType", exposure.type().name());
            if (exposure.channelType() != null) descriptor.put("channelType", exposure.channelType());
            descriptor.put("agentDefinitionId", text(agent, "definitionId"));
            descriptor.put("agentVersionId", text(agent, "versionId"));
            descriptor.put("agentVersion", text(agent, "version"));
            descriptor.put("specDigest", text(agent, "specDigest"));
            descriptor.put("manifestVersion", manifest.path("manifestVersion").intValue());
            descriptor.put("manifestDigest", digest(releaseManifestJson));
            descriptor.put("inputSchema", schema(inputSchema, "input"));
            descriptor.put("outputSchema", schema(outputSchema, "output"));
            descriptor.put("authentication", exposure.policy().authentication());
            descriptor.put("requiredScopes", exposure.policy().requiredScopes().stream().sorted().toList());
            descriptor.put("streaming", exposure.type().name().equals("SSE") || exposure.type().name().equals("A2A")
                    || exposure.type().name().equals("INTERACTION_CHANNEL"));
            return Map.copyOf(descriptor);
        } catch (JacksonException malformed) {
            throw new IllegalArgumentException("invalid release manifest", malformed);
        }
    }

    private Object schema(String value, String name) throws JacksonException {
        if (value == null || value.length() > 1_048_576) throw new IllegalArgumentException(name + " schema is invalid");
        JsonNode parsed = json.readTree(value);
        if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException(name + " schema is not an object");
        return json.convertValue(parsed, Object.class);
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isString()) throw new IllegalArgumentException("release manifest agent is incomplete");
        return node.path(field).stringValue();
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
