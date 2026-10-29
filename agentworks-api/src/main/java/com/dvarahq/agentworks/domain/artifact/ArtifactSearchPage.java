package com.dvarahq.agentworks.domain.artifact;

import java.util.List;

public record ArtifactSearchPage(List<ArtifactMetadata> entries, boolean hasMore) {
    public ArtifactSearchPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
