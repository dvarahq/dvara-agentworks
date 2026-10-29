package com.dvarahq.agentworks.gateway;

import java.util.Set;

/**
 * Provider-neutral gateway SPI (ADR-009, master specification section 17.2). One instance is one configured gateway.
 *
 * <p>Contract, enforced by {@code AiGatewayAdapterConformanceSuite}:
 * <ul>
 *   <li>Contracts use only Core types: no provider SDK, Spring AI, Reactor, or DvaraHQ type crosses this interface.</li>
 *   <li>{@link #resolveModel} is deterministic and offline: no network and no credential.</li>
 *   <li>{@link #invoke} is called only after Core negotiated capabilities; it honors the {@link InvocationPlan} exactly
 *       and never uses a capability the plan did not grant.</li>
 *   <li>Streaming emits deltas through the {@link ChunkSink} in provider order; Core assigns sequence numbers.</li>
 *   <li>An adapter advertising {@link GatewayCapability#CANCELLATION} aborts the provider request when the plan's
 *       signal is cancelled.</li>
 *   <li>Failures are {@link GatewayException}s whose messages contain no credential, prompt, or response body.</li>
 * </ul>
 * Adapters are shared across threads and must be safe for concurrent invocations.
 */
public interface AiGatewayAdapter {

    GatewayDescriptor descriptor();

    default String type() {
        return descriptor().type();
    }

    /** Capabilities this gateway currently supports; negotiation reads this before every invocation. */
    Set<GatewayCapability> capabilities();

    /**
     * Resolves a logical alias to a concrete model identity at release time.
     *
     * @throws GatewayException {@link GatewayErrorCode#MODEL_ALIAS_UNKNOWN} when the alias is not known
     */
    ResolvedModel resolveModel(ModelResolutionRequest request);

    /**
     * Invokes the pinned provider model. Runs on a Core-owned thread; the caller may abandon it on cancellation or
     * deadline, after which any result is discarded.
     */
    AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink);
}
