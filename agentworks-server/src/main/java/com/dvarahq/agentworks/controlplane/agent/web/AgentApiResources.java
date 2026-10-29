package com.dvarahq.agentworks.controlplane.agent.web;

import com.dvarahq.agentworks.spec.AgentDefinition;
import com.dvarahq.agentworks.spec.AgentVersion;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** REST representations for {@code /api/v1/agents}. */
public final class AgentApiResources {
    /** Decimals stay BigDecimal so the returned spec re-canonicalizes to the stored digest. */
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private AgentApiResources() {}

    public record CreateAgentDefinitionRequest(String name, String description) {}

    public record AgentDefinitionResource(UUID id, UUID workspaceId, String name, String description, Instant createdAt) {
        static AgentDefinitionResource of(AgentDefinition definition) {
            return new AgentDefinitionResource(definition.id().value(), definition.workspaceId().value(),
                    definition.name(), definition.description(), definition.createdAt());
        }
    }

    /** {@code spec} is the canonical AgentSpec; recomputing its canonical digest must yield {@code digest}. */
    public record AgentVersionResource(UUID id, UUID definitionId, UUID workspaceId, long sequence, String version,
            String apiVersion, String digest, Instant createdAt, JsonNode spec) {
        static AgentVersionResource of(AgentVersion version) {
            return new AgentVersionResource(version.id().value(), version.definitionId().value(),
                    version.workspaceId().value(), version.sequence(), version.version(),
                    version.spec().spec().apiVersion(), version.digest(), version.createdAt(),
                    CANONICAL.readTree(version.spec().canonicalJson()));
        }
    }
}
