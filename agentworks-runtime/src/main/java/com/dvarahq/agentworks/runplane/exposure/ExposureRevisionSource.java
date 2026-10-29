package com.dvarahq.agentworks.runplane.exposure;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.util.UUID;

/** Reads immutable deployment configuration under workspace scope. */
@FunctionalInterface
public interface ExposureRevisionSource {
    RevisionConfiguration read(WorkspaceId workspace, UUID deploymentId, UUID revisionId);

    record RevisionConfiguration(UUID revisionId, String configurationJson) {}
}
