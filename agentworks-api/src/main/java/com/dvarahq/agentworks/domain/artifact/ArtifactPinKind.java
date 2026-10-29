package com.dvarahq.agentworks.domain.artifact;

/** Durable reasons that independently retain an artifact beyond its ordinary expiry. */
public enum ArtifactPinKind {
    RUN,
    RELEASE,
    EVALUATION,
    AUDIT,
    RETENTION
}
