package com.dvarahq.agentworks.sandbox;

import com.dvarahq.agentworks.domain.sandbox.SandboxOwner;
import com.dvarahq.agentworks.domain.sandbox.SandboxResource;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable authority used around, never inside, external provider calls. */
public interface SandboxLifecycleAuthority {
    SandboxResource reserve(WorkspaceId workspace, UUID sandboxId, SandboxOwner owner, String provider,
            Duration maximumRuntime, Duration provisioningTimeout);

    SandboxResource activate(WorkspaceId workspace, UUID sandboxId);

    SandboxResource requestCleanup(WorkspaceId workspace, UUID sandboxId);

    Optional<SandboxResource> claimCleanup(WorkspaceId workspace, String provider, String worker, Duration lease);

    Optional<SandboxResource> claimCleanup(WorkspaceId workspace, UUID sandboxId, String provider, String worker,
            Duration lease);

    SandboxResource cleanupFailed(WorkspaceId workspace, UUID sandboxId, String worker, long fence,
            String failureCode, Duration retryDelay);

    SandboxResource cleanupSucceeded(WorkspaceId workspace, UUID sandboxId, String worker, long fence);

    Optional<SandboxResource> find(WorkspaceId workspace, UUID sandboxId);

    List<SandboxResource> open(WorkspaceId workspace);

    List<WorkspaceId> workspacesWithOpenResources(String provider);
}
