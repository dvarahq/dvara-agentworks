package com.dvarahq.agentworks.artifact;

import com.dvarahq.agentworks.domain.artifact.ArtifactComputeRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactComputeResult;

/** Bounded, provider-neutral transforms over an explicit set of authorized artifact references. */
public interface ArtifactCompute {
    ArtifactComputeResult execute(ArtifactComputeRequest request);
}
