package com.dvarahq.agentworks.runplane.reconciliation;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleReads;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.RunPlaneComponent;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Run Plane convergence onto approved deployment state (ADR-008). A notification only names a deployment; the
 * reconciler always re-reads the authoritative row through {@link LifecycleReads} and records what it observed,
 * advancing only to a newer deployment generation. Duplicate, stale, reordered, or lost notifications therefore
 * converge, and the observation never redefines desired state. Observations are a non-authoritative local cache
 * keyed by workspace and deployment; runs never read them for correctness, since each claim loads its own pins.
 */
public final class DeploymentReconciler implements RunPlaneComponent {
    /** What this node last observed of a deployment's approved state. */
    public record ObservedDeployment(UUID workspaceId, UUID deploymentId, long generation, UUID desiredReleaseId,
            UUID currentRevisionId, UUID currentRoutingGenerationId, String desiredPlacement,
            String observedPlacement, boolean placementDrift) {}

    private record Key(UUID workspaceId, UUID deploymentId) {}

    private final TenantTransactionTemplate tenants;
    private final LifecycleReads lifecycle;
    private final Map<Key, ObservedDeployment> observed = new ConcurrentHashMap<>();

    public DeploymentReconciler(TenantTransactionTemplate tenants, LifecycleReads lifecycle) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * Re-reads the deployment and records it when its generation is newer than the last observation. Returns true
     * when the observation advanced; a deployment that is absent from the workspace changes nothing.
     */
    public boolean reconcile(WorkspaceId workspace, UUID deploymentId) {
        Objects.requireNonNull(deploymentId, "deploymentId");
        Optional<DeploymentRow> row = tenants.execute(workspace, () -> lifecycle.findDeployment(deploymentId));
        if (row.isEmpty()) return false;
        DeploymentRow deployment = row.get();
        String placement = tenants.execute(workspace, () -> lifecycle.findRevision(deployment.currentRevisionId())
                .map(com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RevisionRow::placement).orElse(null));
        ObservedDeployment priorObservation = observed.get(new Key(workspace.value(), deploymentId));
        String assignment = priorObservation == null ? null : priorObservation.observedPlacement();
        ObservedDeployment next = new ObservedDeployment(workspace.value(), deployment.id(), deployment.generation(),
                deployment.desiredReleaseId(), deployment.currentRevisionId(), deployment.currentRoutingGenerationId(),
                placement, assignment, assignment != null && !Objects.equals(placement, assignment));
        ObservedDeployment kept = observed.merge(new Key(workspace.value(), deploymentId), next,
                (previous, candidate) -> candidate.generation() > previous.generation() ? candidate : previous);
        return kept == next;
    }

    /** Re-reads every deployment this node has observed; converges after a lost notification. */
    public int reconcileObserved() {
        int advanced = 0;
        for (Key key : Map.copyOf(observed).keySet()) {
            if (reconcile(new WorkspaceId(key.workspaceId()), key.deploymentId())) advanced++;
        }
        return advanced;
    }

    public Optional<ObservedDeployment> observed(WorkspaceId workspace, UUID deploymentId) {
        return Optional.ofNullable(observed.get(new Key(workspace.value(), deploymentId)));
    }

    /** Records the scheduler's observed placement and exposes drift without changing desired deployment state. */
    public ObservedDeployment observeAssignment(WorkspaceId workspace, UUID deploymentId, String placement) {
        if (!java.util.Set.of("SHARED", "ISOLATED", "DEDICATED").contains(placement)) {
            throw new IllegalArgumentException("observed placement is invalid");
        }
        reconcile(workspace, deploymentId);
        Key key = new Key(workspace.value(), deploymentId);
        return observed.compute(key, (ignored, current) -> {
            if (current == null) throw new IllegalArgumentException("deployment was not found");
            return new ObservedDeployment(current.workspaceId(), current.deploymentId(), current.generation(),
                    current.desiredReleaseId(), current.currentRevisionId(), current.currentRoutingGenerationId(),
                    current.desiredPlacement(), placement, !Objects.equals(current.desiredPlacement(), placement));
        });
    }
}
