package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.child.ChildFailurePolicy;
import com.dvarahq.agentworks.child.ChildRunBudget;
import com.dvarahq.agentworks.child.ChildRunRecord;
import com.dvarahq.agentworks.child.ChildRunRequest;
import com.dvarahq.agentworks.child.ChildRunStatus;
import com.dvarahq.agentworks.domain.run.RunEventType;
import com.dvarahq.agentworks.domain.run.RunLease;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Framework-neutral agent-as-tool mapping. The surrounding ordinary tool pipeline remains responsible for capability
 * selection, live policy, input/output guardrails and JIT credentials; this boundary replaces only the invocation.
 * A stable tool-call id is the child idempotency key, so recovery observes the same terminal child rather than
 * allocating another. A nonterminal result means the caller released its lease in CHILD_RUN and must stop.
 */
public final class AgentAsTool {
    private final DurableChildRunCoordinator children;
    private final DurableRunEngine runs;

    public AgentAsTool(DurableChildRunCoordinator children, DurableRunEngine runs) {
        this.children = Objects.requireNonNull(children, "children"); this.runs = Objects.requireNonNull(runs, "runs");
    }

    public Result invoke(RunLease lease, String toolCallId, UUID targetDeploymentId, UUID expectedReleaseId,
            String task, Map<String, Object> leastContext, Set<String> allowedTools, ChildRunBudget budget,
            ChildFailurePolicy failurePolicy) {
        ChildRunRecord child = children.start(lease, new ChildRunRequest(targetDeploymentId, expectedReleaseId,
                task, leastContext, allowedTools, budget, failurePolicy, toolCallId));
        return result(lease, child);
    }

    public Result invoke(RunLease lease, ChildRunRequest request) {
        return result(lease, children.start(lease, request));
    }

    private Result result(RunLease lease, ChildRunRecord child) {
        if (!child.status().terminal()) return new Result.Blocked(child.id(), child.childRunId());
        if (child.status() != ChildRunStatus.SUCCEEDED) {
            return new Result.Failed(child.id(), child.childRunId(), child.status().name(), child.terminalCode());
        }
        JsonNode output = runs.history(lease.workspace(), child.childRunId()).stream()
                .filter(event -> event.type() == RunEventType.RUN_SUCCEEDED).reduce((first, second) -> second)
                .map(event -> event.payload().get("output")).orElse(null);
        return new Result.Completed(child.id(), child.childRunId(), output);
    }

    public sealed interface Result {
        UUID relationshipId();
        UUID childRunId();
        record Blocked(UUID relationshipId, UUID childRunId) implements Result {}
        record Completed(UUID relationshipId, UUID childRunId, JsonNode output) implements Result {}
        record Failed(UUID relationshipId, UUID childRunId, String status, String code) implements Result {}
    }
}
