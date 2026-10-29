package com.dvarahq.agentworks.domain.artifact;

/** Closed initial operator set. Operators are deterministic and execute no caller-supplied code. */
public enum ArtifactComputeOperator {
    /** Concatenates input bytes in the request's declared order. */
    CONCATENATE,
    /** Emits a canonical JSON manifest after re-reading and hashing every declared input. */
    SHA256_MANIFEST
}
