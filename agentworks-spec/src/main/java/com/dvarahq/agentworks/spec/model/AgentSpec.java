package com.dvarahq.agentworks.spec.model;

/**
 * Authored {@code dvarahq.com/v1alpha1} agent specification. This is the single canonical value shared by YAML,
 * JSON, REST, and Java SDK authoring. It deliberately carries no environment, release, deployment, promotion,
 * placement, or exposure configuration; those are separate lifecycle artifacts.
 */
public record AgentSpec(String apiVersion, String kind, AgentMetadata metadata, AgentSpecBody spec) {
    public static final String API_VERSION_V1ALPHA1 = "dvarahq.com/v1alpha1";
    public static final String KIND = "Agent";

    /** Creates a specification for the current authored API version. */
    public static AgentSpec v1alpha1(AgentMetadata metadata, AgentSpecBody spec) {
        return new AgentSpec(API_VERSION_V1ALPHA1, KIND, metadata, spec);
    }
}
