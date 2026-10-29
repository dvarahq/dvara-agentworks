package com.dvarahq.agentworks.gateway;

import java.time.Duration;
import java.util.Objects;

/**
 * Everything an adapter receives for one invocation. The request is already reduced to the plan: degraded tool
 * definitions or response formats have been removed by Core.
 *
 * @param providerModel the pinned concrete model; adapters never re-resolve an alias at invocation time
 * @param timeout the invocation deadline. Core enforces it through {@code cancellation}; an adapter may also apply it
 *        as a transport backstop, which matters when the plan does not grant cancellation
 * @param evidence immutable release/run scope and verification evidence supplied by Core, or {@code null} only for
 *        direct adapter/conformance use outside {@link ModelGateway}
 */
public record AdapterInvocation(String providerModel, ModelRequest request, InvocationPlan plan,
        GatewayCredential credential, CancellationSignal cancellation, Duration timeout,
        AdapterInvocationEvidence evidence) {
    /** Compatibility constructor for direct adapter/conformance use outside Core; no release evidence is asserted. */
    public AdapterInvocation(String providerModel, ModelRequest request, InvocationPlan plan,
            GatewayCredential credential, CancellationSignal cancellation, Duration timeout) {
        this(providerModel, request, plan, credential, cancellation, timeout, null);
    }

    public AdapterInvocation {
        Objects.requireNonNull(timeout, "timeout");
        GatewayDescriptor.requireText(providerModel, "providerModel");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(cancellation, "cancellation");
    }
}
