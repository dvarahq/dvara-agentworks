package com.dvarahq.agentworks.domain.artifact;

/** Content-validation state. Only ACCEPTED content may be read. */
public enum ArtifactValidation {
    PENDING,
    ACCEPTED,
    QUARANTINED,
    REJECTED
}
