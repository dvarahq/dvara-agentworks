package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectInvoker;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectOutcome;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader.RunManifest;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A handler's view of one claimed continuation. Every write goes through the run engine under the worker's fenced
 * lease; a write after the lease was lost or the run was cancelled fails with {@code STALE_LEASE}, and the handler
 * must stop.
 */
public interface RunContext {
    RunPins pins();

    RunManifest manifest();

    /** Exact reusable definition selected by the compiled-release cache, when the handler is cache-aware. */
    default Optional<com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition> compiledDefinition() {
        return Optional.empty();
    }

    JsonNode input();

    /** The state of the latest checkpoint, restored when the run resumes or recovers. */
    Optional<JsonNode> checkpointState();

    /** True when this claim took over a run whose previous lease expired. */
    boolean recovered();

    /** Appends worker events; their {@code usage} deltas count against the run's pinned limits. */
    void emit(List<EventDraft> events);

    /**
     * Records a safe boundary. When the cohort was suspended, {@link #suspended()} becomes true, and when an operator
     * asked for a pause, {@link #pauseRequested()}: pause here.
     */
    void checkpoint(Object state);

    /** Executes a modeled external effect at most once per run and key (see {@link DurableRunEngine#executeEffect}). */
    EffectOutcome effect(String key, Object request, EffectInvoker invoker);

    /** True after a write observed that the run's cohort is suspended. */
    boolean suspended();

    /**
     * True once the claim, a write, an effect's outcome, or (for an executor that may be paused mid-exchange) a
     * heartbeat observed an operator's request to pause the run (#183, #194), so a request is seen within one heartbeat
     * at most. It is a hint: it stays true until a {@link #pauseIfRequested()} boundary finds the request withdrawn,
     * so it may still report a request a resume withdrew since. Handlers pause through {@link #pauseIfRequested()}.
     */
    default boolean pauseRequested() {
        return false;
    }

    /**
     * Pauses the run here, at a safe boundary of the handler's choosing (between steps, never inside an effect), if an
     * operator's request is still pending and no effect is in doubt (#183). The check and the pause are one atomic
     * write, so a request a resume withdrew never pauses the run. When it returns true the run is paused and the lease
     * released: the handler writes nothing more and returns {@code RunOutcome.paused(DurableRunEngine.OPERATOR_PAUSE)}.
     * When it returns false the run stays active and the handler continues.
     *
     * <p>Pause is enforced for every handler, cooperative or not: the worker also pauses the run, and stops the
     * handler with {@code PAUSE_REQUESTED}, right after a checkpoint and before an effect starts. A handler that never
     * checkpoints nor starts an effect is paused only at its own boundaries, or ends first.
     */
    default boolean pauseIfRequested() {
        return false;
    }

    /**
     * The authenticated principal that submitted the run, when one did (issue #34). Model invocations run under its
     * identity, and the gateway refuses it unless its workspace owns the pinned release.
     */
    default Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker() {
        return Optional.empty();
    }

    /** The pinned AgentVersion, present only when its stored digest equals the one the release manifest recorded. */
    default Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion() {
        return Optional.empty();
    }

    /** The captured summary-first capability decision, after the run capability boundary has resolved it. */
    default Optional<ResolvedRunCapabilities> resolvedCapabilities() {
        return Optional.empty();
    }

    /** Logical names to opaque credential references from this run's immutable pinned deployment revision. */
    default Map<String, com.dvarahq.agentworks.domain.security.CredentialRef> credentialReferences() {
        return Map.of();
    }

    /** Atomically delegates child work and releases this claim; available only on a configured production worker. */
    default com.dvarahq.agentworks.child.ChildRunRecord startChild(
            com.dvarahq.agentworks.child.ChildRunRequest request) {
        throw new RunEngineException("CHILD_RUN_UNAVAILABLE", "this execution has no child-run authority");
    }

    /** Invokes or replays an agent-as-tool child, including its terminal result when one is already durable. */
    default AgentAsTool.Result invokeChild(com.dvarahq.agentworks.child.ChildRunRequest request) {
        com.dvarahq.agentworks.child.ChildRunRecord child = startChild(request);
        if (!child.status().terminal()) return new AgentAsTool.Result.Blocked(child.id(), child.childRunId());
        return new AgentAsTool.Result.Failed(child.id(), child.childRunId(), child.status().name(),
                child.terminalCode());
    }
}
