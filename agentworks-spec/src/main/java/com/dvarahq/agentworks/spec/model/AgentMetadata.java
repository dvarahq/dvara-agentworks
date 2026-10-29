package com.dvarahq.agentworks.spec.model;

/**
 * Authored identity. {@code namespace} is an authoring label only; tenancy is always the workspace bound to the
 * authenticated request and is never selected by document content.
 */
public record AgentMetadata(String name, String namespace, String version, String description) {
    public static AgentMetadata of(String name, String version) {
        return new AgentMetadata(name, null, version, null);
    }
}
