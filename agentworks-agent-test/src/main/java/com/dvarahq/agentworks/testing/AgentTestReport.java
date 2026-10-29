package com.dvarahq.agentworks.testing;

import com.dvarahq.agentworks.testing.simulation.SimulationEvidence;
import java.time.Instant;
import java.util.*;

/** Machine-readable evidence emitted for every run, including identities needed to reproduce it. */
public record AgentTestReport(int version, String testId, String agent, String workspace, long seed,
        Instant generatedAt, String fixtureDigest, String evaluatorIdentity, Map<String, String> dependencies, AgentTestResult result,
        String traceDigest, SimulationEvidence simulation, List<AgentTestMismatch> mismatches) {
    /** Compatible constructor for consumers of the version-1 evidence contract. */
    public AgentTestReport(int version, String testId, String agent, String workspace, long seed, Instant generatedAt,
            String fixtureDigest, String evaluatorIdentity, Map<String, String> dependencies, AgentTestResult result,
            String traceDigest, List<AgentTestMismatch> mismatches) {
        this(version, testId, agent, workspace, seed, generatedAt, fixtureDigest, evaluatorIdentity, dependencies,
                result, traceDigest, SimulationEvidence.direct(seed), mismatches);
    }
    public AgentTestReport {
        if (version < 1 || version > 2) throw new IllegalArgumentException("unsupported report version");
        if (evaluatorIdentity == null || evaluatorIdentity.isBlank()) throw new IllegalArgumentException("evaluatorIdentity is required");
        dependencies = Map.copyOf(dependencies); Objects.requireNonNull(simulation); mismatches = List.copyOf(mismatches);
    }
}
