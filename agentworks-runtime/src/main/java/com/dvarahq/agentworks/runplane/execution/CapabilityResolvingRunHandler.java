package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunPins;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Resolves and records run capabilities before an executor can build model context. */
public final class CapabilityResolvingRunHandler implements RunHandler {
    private final RunHandler delegate;
    private final RunCapabilityResolver resolver;

    public CapabilityResolvingRunHandler(RunHandler delegate, RunCapabilityResolver resolver) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public RunOutcome execute(RunContext context) throws Exception {
        Optional<ResolvedRunCapabilities> resolved = resolver.resolve(context);
        return delegate.execute(resolved.isEmpty() ? context : new ResolvedContext(context, resolved.get()));
    }

    private record ResolvedContext(RunContext delegate, ResolvedRunCapabilities capabilities) implements RunContext {
        private ResolvedContext {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(capabilities, "capabilities");
        }
        @Override public RunPins pins() { return delegate.pins(); }
        @Override public ReleaseManifestReader.RunManifest manifest() { return delegate.manifest(); }
        @Override public Optional<com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition>
                compiledDefinition() { return delegate.compiledDefinition(); }
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
        @Override public Optional<ResolvedRunCapabilities> resolvedCapabilities() { return Optional.of(capabilities); }
        @Override public java.util.Map<String, com.dvarahq.agentworks.domain.security.CredentialRef>
                credentialReferences() { return delegate.credentialReferences(); }
        @Override public com.dvarahq.agentworks.child.ChildRunRecord startChild(
                com.dvarahq.agentworks.child.ChildRunRequest request) { return delegate.startChild(request); }
        @Override public AgentAsTool.Result invokeChild(com.dvarahq.agentworks.child.ChildRunRequest request) {
            return delegate.invokeChild(request);
        }
    }
}
