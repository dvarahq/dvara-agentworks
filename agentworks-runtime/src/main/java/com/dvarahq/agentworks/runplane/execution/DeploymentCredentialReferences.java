package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Strictly reads opaque credential references from one immutable deployment revision. */
final class DeploymentCredentialReferences {
    private static final Set<String> FIELDS = Set.of(
            "placement", "credentialReferences", "policyIdentities", "placementConstraints", "exposures");
    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern REFERENCE =
            Pattern.compile("^[a-z][a-z0-9+.-]{1,31}://[A-Za-z0-9._~/:@-]{1,240}$");
    private static final JsonMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private DeploymentCredentialReferences() {}

    static Map<String, CredentialRef> read(String configuration) {
        JsonNode root;
        try {
            root = JSON.readTree(Objects.requireNonNull(configuration, "configuration"));
        } catch (JacksonException malformed) {
            throw invalid();
        }
        if (root == null || !root.isObject() || !FIELDS.containsAll(Set.copyOf(root.propertyNames()))) throw invalid();
        JsonNode references = root.get("credentialReferences");
        if (references == null || !references.isObject()) throw invalid();
        Map<String, CredentialRef> result = new LinkedHashMap<>();
        references.properties().forEach(entry -> {
            if (!NAME.matcher(entry.getKey()).matches() || !entry.getValue().isString()
                    || !REFERENCE.matcher(entry.getValue().stringValue()).matches()) throw invalid();
            result.put(entry.getKey(), new CredentialRef(entry.getValue().stringValue()));
        });
        return Map.copyOf(result);
    }

    private static RunEngineException invalid() {
        return new RunEngineException("INVALID_DEPLOYMENT_CONFIGURATION",
                "the pinned deployment credential references are not readable");
    }
}
