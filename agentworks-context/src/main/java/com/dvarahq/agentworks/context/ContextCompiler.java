package com.dvarahq.agentworks.context;

import java.util.UUID;

/** Provider-neutral OSS compiler contract. */
public interface ContextCompiler {
    CompiledContext compile(ContextCompilationRequest request);
    int retire(UUID workspaceId, UUID releaseId, UUID deploymentRevisionId);
}
