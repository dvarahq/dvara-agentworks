package com.dvarahq.agentworks.artifact;

import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchPage;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;

import java.util.Set;

/**
 * Provider-neutral immutable artifact boundary. Implementations consume a write stream before returning, publish
 * atomically only after size/digest validation, scope every resolution to the request owner, and never expose partial
 * or non-accepted content.
 */
public interface ArtifactStore extends AutoCloseable {
    Set<ArtifactStoreCapability> capabilities();

    ArtifactRef put(ArtifactWrite request);

    ArtifactContent read(ArtifactRead request);

    ArtifactSearchPage search(ArtifactSearchRequest request);

    @Override
    default void close() {}
}
