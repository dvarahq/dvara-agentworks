package com.dvarahq.agentworks.domain.run;

import java.util.Objects;
import java.util.UUID;

/**
 * The immutable AgentVersion a run's release pins, as stored: its canonical spec document and digest. The engine
 * hands it to a run only when the digest equals the one the release manifest recorded, so an executor reads exactly
 * the agent the release approved (issue #34).
 */
public record PinnedAgentVersion(UUID versionId, String specJson, String specDigest) {
    public PinnedAgentVersion {
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(specJson, "specJson");
        Objects.requireNonNull(specDigest, "specDigest");
    }

    @Override
    public String toString() {
        return "PinnedAgentVersion[versionId=" + versionId + ", specDigest=" + specDigest + "]";
    }
}
