package com.dvarahq.agentworks.runplane.compiled;

import com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition;
import com.dvarahq.agentworks.domain.run.RunPins;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.runplane.execution.AgentAsTool;
import com.dvarahq.agentworks.runplane.execution.DurableRunEngine;
import com.dvarahq.agentworks.runplane.execution.ReleaseManifestReader;
import com.dvarahq.agentworks.runplane.execution.ResolvedRunCapabilities;
import com.dvarahq.agentworks.runplane.execution.RunContext;
import com.dvarahq.agentworks.runplane.execution.RunHandler;
import com.dvarahq.agentworks.runplane.execution.RunOutcome;
import com.dvarahq.agentworks.runplane.execution.RunBackpressureException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Acquires exact compiled material for the lifetime of one claimed continuation. */
public final class CompiledReleaseCachingRunHandler implements RunHandler {
    private final RunHandler delegate;
    private final CompiledReleaseCache cache;

    public CompiledReleaseCachingRunHandler(RunHandler delegate, CompiledReleaseCache cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public RunOutcome execute(RunContext context) throws Exception {
        CompiledReleaseCache.Key key = key(context);
        try (CompiledReleaseCache.Lease lease = acquire(key, context)) {
            return delegate.execute(new CompiledContext(context, lease.value().definition()));
        }
    }

    private CompiledReleaseCache.Lease acquire(CompiledReleaseCache.Key key, RunContext context) {
        try { return cache.acquire(key, () -> compile(context)); }
        catch (CompiledReleaseCache.CacheException saturated) {
            if (saturated.code().equals("COMPILED_CACHE_FULL") || saturated.code().equals("COMPILED_CACHE_CLOSED")
                    || saturated.code().equals("COMPILED_RELEASE_CLEANUP_PENDING")) {
                throw new RunBackpressureException("COMPILED_RELEASE_UNAVAILABLE",
                        "compiled release capacity is temporarily unavailable");
            }
            throw saturated;
        }
    }

    static CompiledReleaseCache.Key key(RunContext context) {
        RunPins pins = context.pins();
        ReleaseManifestReader.RunManifest manifest = context.manifest();
        Object modelIdentities = manifest.models() == null ? null : manifest.models().identities();
        Object externalExecutor = manifest.externalExecutor() == null ? null : manifest.externalExecutor().orElse(null);
        String dependencies = hash(manifest.specDigest() + "|" + modelIdentities + '|'
                + manifest.pinnedCapabilities() + '|' + manifest.pinnedSkills() + '|'
                + externalExecutor);
        String policy = hash(optionalValue(manifest.executionPolicy()) + "|"
                + optionalValue(manifest.sandboxConfiguration()) + '|'
                + optionalValue(manifest.contextPolicy()) + '|' + optionalValue(manifest.memoryPolicy()));
        return new CompiledReleaseCache.Key(new CompiledReleaseKey(pins.workspaceId(), pins.releaseId(),
                manifest.manifestVersion(), dependencies), new DeploymentOverlayKey(pins.deploymentId(),
                pins.revisionId(), policy, dependencies));
    }

    private static CompiledRelease compile(RunContext context) {
        List<CredentialRef> references = context.credentialReferences().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).distinct().toList();
        return new CompiledRelease(new CompiledAgentDefinition(context.manifest().executorType(), references), List.of());
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Object optionalValue(Optional<?> value) {
        return value == null ? null : value.orElse(null);
    }

    private record CompiledContext(RunContext delegate, CompiledAgentDefinition definition) implements RunContext {
        private CompiledContext { Objects.requireNonNull(delegate); Objects.requireNonNull(definition); }
        @Override public RunPins pins() { return delegate.pins(); }
        @Override public ReleaseManifestReader.RunManifest manifest() { return delegate.manifest(); }
        @Override public Optional<CompiledAgentDefinition> compiledDefinition() { return Optional.of(definition); }
        @Override public JsonNode input() { return delegate.input(); }
        @Override public Optional<JsonNode> checkpointState() { return delegate.checkpointState(); }
        @Override public boolean recovered() { return delegate.recovered(); }
        @Override public void emit(List<DurableRunEngine.EventDraft> events) { delegate.emit(events); }
        @Override public void checkpoint(Object state) { delegate.checkpoint(state); }
        @Override public DurableRunEngine.EffectOutcome effect(String key, Object request,
                DurableRunEngine.EffectInvoker invoker) { return delegate.effect(key, request, invoker); }
        @Override public boolean suspended() { return delegate.suspended(); }
        @Override public boolean pauseRequested() { return delegate.pauseRequested(); }
        @Override public boolean pauseIfRequested() { return delegate.pauseIfRequested(); }
        @Override public Optional<com.dvarahq.agentworks.domain.run.RunInvoker> invoker() { return delegate.invoker(); }
        @Override public Optional<com.dvarahq.agentworks.domain.run.PinnedAgentVersion> agentVersion() {
            return delegate.agentVersion();
        }
        @Override public Optional<ResolvedRunCapabilities> resolvedCapabilities() { return delegate.resolvedCapabilities(); }
        @Override public Map<String, CredentialRef> credentialReferences() { return delegate.credentialReferences(); }
        @Override public com.dvarahq.agentworks.child.ChildRunRecord startChild(
                com.dvarahq.agentworks.child.ChildRunRequest request) { return delegate.startChild(request); }
        @Override public AgentAsTool.Result invokeChild(com.dvarahq.agentworks.child.ChildRunRequest request) {
            return delegate.invokeChild(request);
        }
    }
}
