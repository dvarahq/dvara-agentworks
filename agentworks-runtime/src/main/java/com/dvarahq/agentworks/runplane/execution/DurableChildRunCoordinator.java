package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.child.ChildRunBudget;
import com.dvarahq.agentworks.child.ChildRunException;
import com.dvarahq.agentworks.child.ChildFailurePolicy;
import com.dvarahq.agentworks.child.ChildRunRecord;
import com.dvarahq.agentworks.child.ChildRunRequest;
import com.dvarahq.agentworks.child.ChildRunStatus;
import com.dvarahq.agentworks.domain.run.RunLease;
import com.dvarahq.agentworks.domain.run.RunLifecycle;
import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunRecord;
import com.dvarahq.agentworks.domain.run.RunUsage;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionRejectedException;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.storage.child.JdbcChildRunAuthority;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Parent-directed child orchestration. Creation, the parent's durable block, the immutable relationship and child
 * queueing share one local Spring JDBC transaction. Child execution uses ordinary admission and therefore its own
 * approved release, deployment revision, routing generation, cohort, limits, policy and credential references.
 */
public final class DurableChildRunCoordinator {
    public static final String SYSTEM_ACTOR = "system:child-run";

    public record Policy(int maxDepth, int maxFanOut, int maxTotalChildren, ChildRunBudget maximumChildBudget) {
        public Policy {
            if (maxDepth < 1 || maxDepth > 64 || maxFanOut < 1 || maxFanOut > 10_000
                    || maxTotalChildren < 1 || maxTotalChildren > 1_000_000) {
                throw new IllegalArgumentException("child policy is out of range");
            }
            Objects.requireNonNull(maximumChildBudget, "maximumChildBudget");
        }
        public Policy(int maxDepth, int maxFanOut, int maxTotalChildren) {
            this(maxDepth, maxFanOut, maxTotalChildren,
                    new ChildRunBudget(1_000_000, 10_000, 10_000, 86_400_000, new BigDecimal("1000.000000")));
        }
        public static Policy defaults() { return new Policy(8, 32, 256); }
    }

    private final TransactionTemplate transactions;
    private final LifecycleRetry retry;
    private final DurableRunEngine runs;
    private final RunAdmissionService admissions;
    private final JdbcChildRunAuthority children;
    private final Policy policy;

    public DurableChildRunCoordinator(TransactionTemplate transactions, LifecycleRetry retry, DurableRunEngine runs,
            RunAdmissionService admissions, JdbcChildRunAuthority children, Policy policy) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.retry = Objects.requireNonNull(retry, "retry"); this.runs = Objects.requireNonNull(runs, "runs");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.children = Objects.requireNonNull(children, "children"); this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** Starts or replays one child. Only a live parent lease can reach the creation path. */
    public ChildRunRecord start(RunLease parentLease, ChildRunRequest request) {
        Objects.requireNonNull(parentLease, "parentLease"); Objects.requireNonNull(request, "request");
        return required("child-run.start", () -> {
            RunRecord parent = runs.run(parentLease.workspace(), parentLease.runId());
            // limits() locks the causal parent and root in a deterministic order. Replay only after that lock so a
            // concurrent creator which was invisible at transaction start has committed before this lookup.
            JdbcChildRunAuthority.Limits current = children.limits(parentLease.workspace(), parent.id());
            ChildRunRecord replay = children.replay(parentLease.workspace(), parentLease.runId(), request).orElse(null);
            if (replay != null) return replay;
            if (parent.lifecycle() != RunLifecycle.ACTIVE) throw failure(ChildRunException.Code.PARENT_NOT_ACTIVE,
                    "only an active lease holder can create a child");
            int depth = current.depth() + 1;
            if (depth > policy.maxDepth()) throw failure(ChildRunException.Code.DEPTH_LIMIT, "child depth limit reached");
            if (current.fanOut() >= policy.maxFanOut()) throw failure(ChildRunException.Code.FAN_OUT_LIMIT,
                    "parent fan-out limit reached");
            if (current.totalChildren() >= policy.maxTotalChildren()) throw failure(ChildRunException.Code.CHILD_LIMIT,
                    "root child limit reached");
            requireBudget(parent, request.budget(), policy.maximumChildBudget());
            if (Math.addExact(current.reservedTokens(), request.budget().maxTokens()) > policy.maximumChildBudget().maxTokens()
                    || current.reservedCostUsd().add(request.budget().maxCostUsd())
                        .compareTo(policy.maximumChildBudget().maxCostUsd()) > 0) {
                throw failure(ChildRunException.Code.BUDGET_EXCEEDED,
                        "root child reservations exceed the aggregate child allowance");
            }

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("prompt", delegatedPrompt(request));
            input.put("task", request.task());
            input.put("context", request.context());
            input.put("allowedTools", request.tools().stream().sorted().toList());
            input.put("delegatedBudget", request.budget());
            String admissionKey = "child:" + parent.id() + ":" + request.idempotencyKey();
            RunLimits delegated = new RunLimits(request.budget().maxTokens(), (long) request.budget().maxModelCalls(),
                    (long) request.budget().maxToolCalls(), request.budget().maxRuntimeMillis(),
                    request.budget().maxCostUsd());
            var admitted = admissions.admitRoot(parentLease.workspace(), request.targetDeploymentId(), admissionKey,
                    input, new RunAdmissionService.Options(false, parent.pins().traceId(), SYSTEM_ACTOR,
                            runs.invoker(parentLease.workspace(), parent.id()).orElse(null), delegated,
                            new RunAdmissionService.CausalParent(parent.id(), parent.pins().rootRunId())));
            RunRecord child = runs.run(parentLease.workspace(), admitted.run().id());
            if (!child.pins().releaseId().equals(request.expectedReleaseId())) {
                throw failure(ChildRunException.Code.RELEASE_MISMATCH,
                        "the target deployment does not currently route to the requested approved release");
            }
            UUID relationshipId = UUID.randomUUID();
            runs.blockChild(parentLease, relationshipId, child.id(), child.pins().releaseId(),
                    child.pins().deploymentId(), depth);
            ChildRunRecord relationship = children.create(parentLease.workspace(), relationshipId, parent.pins(),
                    child.pins(), depth, request);
            runs.enqueue(parentLease.workspace(), child.id(), SYSTEM_ACTOR);
            return children.running(parentLease.workspace(), relationship.id());
        });
    }

    /**
     * Reconciles terminal children and parent cancellation. It is safe after restart and under duplicate calls: the
     * relationship outcome and parent resume have one transaction and one winner.
     */
    public int reconcile(WorkspaceId workspace, int limit) {
        int resolved = 0;
        for (ChildRunRecord relationship : children.openChildren(workspace, limit)) {
            boolean changed = required("child-run.reconcile", () -> {
                ChildRunRecord current = children.findChild(workspace, relationship.id()).orElseThrow();
                if (current.status().terminal()) return false;
                RunRecord parent = runs.run(workspace, current.parentRunId());
                RunRecord child = runs.run(workspace, current.childRunId());
                if (parent.lifecycle().terminal() && !child.lifecycle().terminal()) {
                    runs.cancel(workspace, child.id(), SYSTEM_ACTOR, "parent " + parent.lifecycle().name().toLowerCase());
                    child = runs.run(workspace, child.id());
                }
                if (!child.lifecycle().terminal()) return false;
                ChildRunStatus status = switch (child.lifecycle()) {
                    case SUCCEEDED -> ChildRunStatus.SUCCEEDED;
                    case FAILED -> ChildRunStatus.FAILED;
                    case CANCELLED -> ChildRunStatus.CANCELLED;
                    default -> throw new IllegalStateException("child is not terminal");
                };
                String code = child.summary().terminalCode() == null ? status.name() : child.summary().terminalCode();
                ChildRunRecord terminal = children.terminal(workspace, current.id(), status, code);
                if (!parent.lifecycle().terminal()) {
                    if (terminal.status() != ChildRunStatus.SUCCEEDED
                            && terminal.request().failurePolicy() == ChildFailurePolicy.FAIL_PARENT) {
                        runs.failRun(workspace, terminal.parentRunId(), "CHILD_RUN_FAILED", SYSTEM_ACTOR,
                                "child " + terminal.childRunId() + " ended as " + terminal.status()
                                        + " (" + terminal.terminalCode() + ")");
                    } else {
                        runs.resumeChild(workspace, terminal, SYSTEM_ACTOR);
                    }
                }
                return true;
            });
            if (changed) resolved++;
        }
        return resolved;
    }

    /** OSS 1.0 truthfully refuses sandbox restore across failover; #167 owns executable restore evidence. */
    public void requireSandboxRestoreAcrossFailover() {
        throw failure(ChildRunException.Code.SANDBOX_RESTORE_UNSUPPORTED,
                "sandbox restore across failover is unavailable in OSS 1.0");
    }

    private static String delegatedPrompt(ChildRunRequest request) {
        if (request.context().isEmpty()) return request.task();
        return request.task() + "\n\nDelegated context (JSON; use only for this task):\n"
                + RunEventCodec.standard().canonicalPayload(request.context());
    }

    private static void requireBudget(RunRecord parent, ChildRunBudget requested, ChildRunBudget maximum) {
        if (requested.maxTokens() > maximum.maxTokens()
                || requested.maxModelCalls() > maximum.maxModelCalls()
                || requested.maxToolCalls() > maximum.maxToolCalls()
                || requested.maxRuntimeMillis() > maximum.maxRuntimeMillis()
                || requested.maxCostUsd().compareTo(maximum.maxCostUsd()) > 0) {
            throw failure(ChildRunException.Code.BUDGET_EXCEEDED,
                    "child budget exceeds the platform child allowance");
        }
        RunLimits limits = parent.pins().limits(); RunUsage used = parent.summary().usage();
        if (exceeds(requested.maxTokens(), limits.maxTotalTokens(), used.totalTokens())
                || exceeds(requested.maxModelCalls(), limits.maxModelCalls(), used.modelCalls())
                || exceeds(requested.maxToolCalls(), limits.maxToolCalls(), used.toolCalls())
                || exceeds(requested.maxRuntimeMillis(), limits.maxRuntimeMillis(), used.runtimeMillis())
                || limits.maxCostUsd() != null
                    && requested.maxCostUsd().compareTo(limits.maxCostUsd().subtract(used.estimatedCostUsd()).max(BigDecimal.ZERO)) > 0) {
            throw failure(ChildRunException.Code.BUDGET_EXCEEDED,
                    "child budget exceeds the parent's remaining pinned allowance");
        }
    }
    private static boolean exceeds(long requested, Long limit, long used) {
        return limit != null && requested > Math.max(0, limit - used);
    }
    private <T> T required(String name, Supplier<T> work) {
        return retry.execute(name, () -> Objects.requireNonNull(transactions.execute(status -> work.get()), "transaction result"));
    }
    private static ChildRunException failure(ChildRunException.Code code, String message) {
        return new ChildRunException(code, message);
    }
}
