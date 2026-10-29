package com.dvarahq.agentworks.domain.external;

/** Java-side policy mediation. Implementations must return opaque artifact references, never raw large payloads. */
public interface ExternalInteractionHandler {
    String invokeTool(String request);

    String transferArtifact(String artifactReference);
}
