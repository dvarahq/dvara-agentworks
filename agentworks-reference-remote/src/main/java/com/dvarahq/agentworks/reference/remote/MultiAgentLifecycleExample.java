package com.dvarahq.agentworks.reference.remote;

import com.dvarahq.agentworks.sdk.lifecycle.CredentialPolicyRef;
import com.dvarahq.agentworks.sdk.lifecycle.ExecutionPin;
import com.dvarahq.agentworks.sdk.lifecycle.QuiescenceReport;
import com.dvarahq.agentworks.sdk.lifecycle.RoutingTargetRef;
import com.dvarahq.agentworks.sdk.lifecycle.RunLineage;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Pure SDK example of promotion cutoff, old-compatible continuation, session rerouting, and retirement blockers. */
public final class MultiAgentLifecycleExample {
    private MultiAgentLifecycleExample() { }

    public static Scenario demonstrate() {
        UUID workspace = uuid(1); UUID releaseOne = uuid(2); UUID releaseTwo = uuid(3);
        UUID revisionOne = uuid(4); UUID revisionTwo = uuid(5); UUID cohortOne = uuid(6);
        UUID oldRun = uuid(7); UUID child = uuid(8); UUID newSessionRun = uuid(9);
        ExecutionPin oldPin = pin(workspace, oldRun, releaseOne, revisionOne, cohortOne,
                new RunLineage(oldRun, oldRun, null, RunLineage.Kind.ROOT), 10);
        ExecutionPin oldChild = pin(workspace, child, releaseOne, revisionOne, cohortOne,
                new RunLineage(child, oldRun, oldRun, RunLineage.Kind.CHILD), 10);
        ExecutionPin newPin = pin(workspace, newSessionRun, releaseTwo, revisionTwo, uuid(11),
                new RunLineage(newSessionRun, newSessionRun, null, RunLineage.Kind.ROOT), 12);
        QuiescenceReport blocked = new QuiescenceReport(workspace, cohortOne, 2, Instant.EPOCH,
                QuiescenceReport.Authority.POSTGRESQL, Map.of("CHILD_WORK", 1L));
        return new Scenario(oldPin, oldChild, newPin, blocked);
    }

    private static ExecutionPin pin(UUID workspace, UUID run, UUID release, UUID revision, UUID cohort,
                                    RunLineage lineage, int identity) {
        return new ExecutionPin(workspace, run, release, revision, cohort,
                new RoutingTargetRef(uuid(identity), uuid(identity + 20), revision, 10_000), lineage,
                new CredentialPolicyRef("credential://reference-agent", "policy-v1"));
    }
    private static UUID uuid(long value) { return new UUID(0, value); }

    public record Scenario(ExecutionPin oldRunResume, ExecutionPin oldCompatibleChild,
                           ExecutionPin newSessionRun, QuiescenceReport retirementBlocker) { }
}
