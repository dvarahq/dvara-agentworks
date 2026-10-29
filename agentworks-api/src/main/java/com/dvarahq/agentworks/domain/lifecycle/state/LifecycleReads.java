package com.dvarahq.agentworks.domain.lifecycle.state;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.CohortRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.DeploymentRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.ReleaseRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RevisionRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingGenerationRow;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RoutingTargetRow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Run Plane port onto approved lifecycle state. Storage adapters implement it; it exposes no control-plane
 * mutation, so the Run Plane depends on this contract rather than on a JDBC repository.
 */
public interface LifecycleReads {
    Optional<DeploymentRow> findDeployment(UUID id);

    Optional<RoutingGenerationRow> findRoutingGeneration(UUID id);

    List<RoutingTargetRow> routingTargets(UUID generationId);

    Optional<CohortRow> findCohort(UUID id);

    /** The immutable cohort created for a deployment revision, when present. */
    default Optional<CohortRow> findCohortForRevision(UUID revisionId) { return Optional.empty(); }

    Optional<RevisionRow> findRevision(UUID id);

    Optional<ReleaseRow> findRelease(UUID id);
}
