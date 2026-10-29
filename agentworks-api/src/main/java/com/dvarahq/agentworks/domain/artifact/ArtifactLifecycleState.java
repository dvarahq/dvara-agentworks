package com.dvarahq.agentworks.domain.artifact;

/** Mutable retention state around immutable artifact content. */
public enum ArtifactLifecycleState {
    ACTIVE,
    EXPIRED,
    DELETED
}
