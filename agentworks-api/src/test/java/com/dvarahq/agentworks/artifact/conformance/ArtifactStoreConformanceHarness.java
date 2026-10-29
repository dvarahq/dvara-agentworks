package com.dvarahq.agentworks.artifact.conformance;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;

import java.time.Duration;

/** Deterministic fault/clock controls required by the portable ArtifactStore TCK. */
public interface ArtifactStoreConformanceHarness extends AutoCloseable {
    ArtifactStore store();

    void advance(Duration duration);

    void quarantine(ArtifactOwner owner, ArtifactRef reference);

    long publishedCount(ArtifactOwner owner);

    @Override
    default void close() {
        store().close();
    }
}
