package com.dvarahq.agentworks.agent.springai;

import com.dvarahq.agentworks.domain.run.RunLimits;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectInvoker;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectOutcome;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EventDraft;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader.RunManifest;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** A run context that records emitted events in memory, for unit tests of the executor's building blocks. */
final class RecordingRunContext implements RunContext {
    final List<EventDraft> events = new CopyOnWriteArrayList<>();
    final RunPins pins;
    volatile boolean suspended;
    volatile RuntimeException failWrites;
    /** When set, every write signals {@link #writing} and then waits until this latch opens: a slow database. */
    volatile java.util.concurrent.CountDownLatch holdWrites;
    final java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
    volatile RunManifest manifest;
    volatile JsonNode input;
    volatile com.dvarahq.agentworks.domain.run.RunInvoker invoker;
    volatile com.dvarahq.agentworks.domain.run.PinnedAgentVersion version;
    volatile com.dvarahq.agentworks.runplane.execution.ResolvedRunCapabilities resolvedCapabilities;
    volatile Map<String, com.dvarahq.agentworks.domain.security.CredentialRef> credentialReferences = Map.of();
    final List<Object> checkpoints = new CopyOnWriteArrayList<>();
    final List<String> effectKeys = new CopyOnWriteArrayList<>();
    final List<Object> effectRequests = new CopyOnWriteArrayList<>();
    final List<String> externalEffectKeys = new CopyOnWriteArrayList<>();
    final AtomicInteger effectInvocations = new AtomicInteger();
    volatile boolean effectInFlight;
    private final Map<String, RecordedEffect> effects = new java.util.concurrent.ConcurrentHashMap<>();
    volatile JsonNode checkpointState;
    volatile java.util.function.Function<com.dvarahq.agentworks.child.ChildRunRequest,
            com.dvarahq.agentworks.child.ChildRunRecord> childStarter;
    volatile java.util.function.Function<com.dvarahq.agentworks.child.ChildRunRequest,
            com.dvarahq.agentworks.runplane.execution.AgentAsTool.Result> childInvoker;

    private record RecordedEffect(String request, JsonNode result, String failure) {}

    RecordingRunContext() {
        UUID id = UUID.randomUUID();
        pins = new RunPins(id, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ROOT", UUID.randomUUID(), 0,
                null, id, null, DurableRunEngine.newTraceId(), 2, RunLimits.UNBOUNDED, Instant.now());
    }

    @Override
    public RunPins pins() {
        return pins;
    }

    @Override
    public RunManifest manifest() {
        if (manifest == null) throw new UnsupportedOperationException();
        return manifest;
    }

    @Override
    public JsonNode input() {
        if (input == null) throw new UnsupportedOperationException();
        return input;
    }

    @Override
    public Optional<JsonNode> checkpointState() {
        return Optional.ofNullable(checkpointState);
    }

    @Override
    public boolean recovered() {
        return false;
    }

    @Override
    public void emit(List<EventDraft> drafts) {
        if (effectInFlight) {
            throw new com.dvarahq.agentworks.runplane.execution.RunEngineException("STALE_LEASE",
                    "the effect invoker cannot write with the pre-effect lease");
        }
        if (failWrites != null) throw failWrites;
        java.util.concurrent.CountDownLatch hold = holdWrites;
        if (hold != null) {
            writing.countDown();
            try {
                hold.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        events.addAll(drafts);
    }

    @Override
    public void checkpoint(Object state) {
        if (failWrites != null) throw failWrites;
        checkpoints.add(state);
    }

    @Override
    public Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker() {
        return Optional.ofNullable(invoker);
    }

    @Override
    public Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion() {
        return Optional.ofNullable(version);
    }

    @Override
    public Optional<com.dvarahq.agentworks.runplane.execution.ResolvedRunCapabilities> resolvedCapabilities() {
        return Optional.ofNullable(resolvedCapabilities);
    }

    @Override
    public Map<String, com.dvarahq.agentworks.domain.security.CredentialRef> credentialReferences() {
        return credentialReferences;
    }

    @Override
    public com.dvarahq.agentworks.child.ChildRunRecord startChild(
            com.dvarahq.agentworks.child.ChildRunRequest request) {
        if (childStarter == null) return RunContext.super.startChild(request);
        return childStarter.apply(request);
    }

    @Override
    public com.dvarahq.agentworks.runplane.execution.AgentAsTool.Result invokeChild(
            com.dvarahq.agentworks.child.ChildRunRequest request) {
        if (childInvoker != null) return childInvoker.apply(request);
        return RunContext.super.invokeChild(request);
    }

    @Override
    public EffectOutcome effect(String key, Object request, EffectInvoker invoker) {
        effectKeys.add(key);
        effectRequests.add(request);
        var codec = com.dvarahq.agentworks.runplane.execution.RunEventCodec.standard();
        String canonical = codec.canonicalPayload(Map.of("request", request));
        RecordedEffect existing = effects.get(key);
        String externalKey = com.dvarahq.agentworks.runplane.execution.RunEventCodec.sha256(pins.id() + "/" + key)
                .substring("sha256:".length());
        if (existing != null) {
            if (!existing.request().equals(canonical)) {
                throw new com.dvarahq.agentworks.runplane.execution.RunEngineException("EFFECT_KEY_REUSED",
                        "effect key was reused for another request");
            }
            return new EffectOutcome(existing.result(), existing.failure(), true, null, externalKey, false);
        }
        try {
            effectInvocations.incrementAndGet();
            externalEffectKeys.add(externalKey);
            effectInFlight = true;
            JsonNode result;
            try {
                result = invoker.invoke(externalKey, codec.mapper().valueToTree(request));
            } finally {
                effectInFlight = false;
            }
            effects.put(key, new RecordedEffect(canonical, result, null));
            return new EffectOutcome(result, null, false, null, externalKey, false);
        } catch (com.dvarahq.agentworks.runplane.execution.DurableRunEngine.EffectFailedException failed) {
            effects.put(key, new RecordedEffect(canonical, null, failed.getMessage()));
            return new EffectOutcome(null, failed.getMessage(), false, null, externalKey, false);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public boolean suspended() {
        return suspended;
    }

    List<String> types() {
        return events.stream().map(event -> event.type().name()).toList();
    }
}
