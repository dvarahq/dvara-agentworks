package com.dvarahq.agentworks.gateway;

import com.dvarahq.agentworks.credential.CredentialAuditSink;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.gateway.CancellationSignal.Reason;
import com.dvarahq.agentworks.gateway.CapabilityNegotiator.Negotiation;
import com.dvarahq.agentworks.gateway.GatewayEvent.CapabilityDegraded;
import com.dvarahq.agentworks.gateway.GatewayEvent.ModelInvocationCompleted;
import com.dvarahq.agentworks.gateway.GatewayEvent.ModelInvocationFailed;
import com.dvarahq.agentworks.gateway.GatewayEvent.ModelInvocationAttempted;
import com.dvarahq.agentworks.gateway.GatewayEvent.ModelFallbackSelected;
import com.dvarahq.agentworks.gateway.GatewayEvent.ModelRouteStateChanged;
import com.dvarahq.agentworks.gateway.GatewayEvent.ModelUsageReconciled;
import com.dvarahq.agentworks.gateway.GatewayEvent.Scope;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core's single entry point for invoking a pinned model. For every invocation, in this order and before any
 * credential resolution or provider contact, it:
 * <ol>
 *   <li>refuses a principal whose workspace differs from the pinned release's workspace;</li>
 *   <li>refuses a gateway that is missing or whose adapter type or protocol differs from the pin;</li>
 *   <li>negotiates the capabilities the request needs against the adapter under the release-captured policy, failing
 *       on a missing required capability and recording an event for each permitted degradation;</li>
 *   <li>honors an already-cancelled caller signal.</li>
 * </ol>
 * It then runs the adapter on a virtual thread under a deadline, resolves the credential just in time, assigns chunk
 * sequence numbers, and records a completion or failure event. The pinned provider model is always the one invoked;
 * no alias is resolved here, so a run cannot drift when an operator changes a gateway's alias catalog.
 *
 * <p>Threading: streamed chunks are delivered on the invocation's virtual thread, one at a time. Thread-local context
 * of the caller (security, MDC, transactions) is not present there. When the call ends early (deadline, cancellation,
 * listener failure), Core waits up to {@link #LISTENER_QUIESCE} for a chunk callback already in progress to return, so
 * no callback is running when the caller regains control unless the listener itself blocks longer than that.
 *
 * <p>Every failure is a {@link GatewayException} with a failure event, except an exception or error thrown by the
 * caller's own listener (rethrown as-is after a {@code CANCELLED} event), one thrown by the event sink while recording
 * a degradation (rethrown as-is, before contact), and a {@link VirtualMachineError} (rethrown as-is after a failure
 * event). Any other {@link Error} from an adapter becomes {@code ADAPTER_FAILURE}.
 */
public final class ModelGateway {

    /** How long an early-ended call waits for an in-progress chunk callback to return. */
    public static final Duration LISTENER_QUIESCE = Duration.ofSeconds(5);

    private final GatewayRegistry registry;
    private final CredentialProvider credentials;
    private final CredentialAuditSink credentialAudit;
    private final GatewayEventSink events;
    private final Clock clock;
    private final ConcurrentHashMap<RouteKey, RouteGuard> routes = new ConcurrentHashMap<>();

    /**
     * @param credentials the JIT credential authority, or {@code null}; gateways configured with a credential reference
     *        then fail with {@link GatewayErrorCode#CREDENTIAL_UNAVAILABLE}
     * @param credentialAudit required whenever {@code credentials} is used
     */
    public ModelGateway(GatewayRegistry registry, CredentialProvider credentials, CredentialAuditSink credentialAudit,
            GatewayEventSink events, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.credentials = credentials;
        this.credentialAudit = credentialAudit;
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A blocking invocation. */
    public ModelResponse call(ModelInvocation invocation) {
        return invoke(Objects.requireNonNull(invocation, "invocation"), null);
    }

    /**
     * A streaming invocation: ordered chunks go to {@code listener}, and the returned response's content equals their
     * concatenation. If streaming is degraded by policy, the whole content arrives as chunk 0.
     */
    public ModelResponse stream(ModelInvocation invocation, ModelChunkListener listener) {
        return invoke(Objects.requireNonNull(invocation, "invocation"), Objects.requireNonNull(listener, "listener"));
    }

    /** Secret-free readiness/saturation snapshot for operator health and metrics bridges. */
    public List<ModelRouteHealth> health() {
        return routes.entrySet().stream().map(entry -> entry.getValue().health(entry.getKey()))
                .sorted(java.util.Comparator.comparing(ModelRouteHealth::gatewayId)
                        .thenComparing(ModelRouteHealth::providerModel)).toList();
    }

    public record ModelRouteHealth(java.util.UUID workspaceId, java.util.UUID releaseId, ModelRole role,
            String gatewayId, String providerModel, String breakerState, int active, int capacity, int failures) { }

    /**
     * Normalizes a delayed authoritative provider usage report for an invocation already recorded with an estimate.
     * Only the primary or a concrete release-captured fallback route is accepted. This method emits content-free
     * discrepancy evidence and performs no provider call or accounting mutation.
     */
    public ModelUsage.Reconciliation reconcileUsage(ModelInvocation invocation, ResolvedModelIdentity servedRoute,
            ModelUsage original, ModelUsage authoritative) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(servedRoute, "servedRoute");
        boolean captured = invocation.model().identity().equals(servedRoute)
                || invocation.model().resilience().fallbacks().contains(servedRoute);
        if (!captured) {
            throw new GatewayException(GatewayErrorCode.GATEWAY_IDENTITY_CHANGED,
                    "usage reconciliation route was not captured by the release");
        }
        ModelUsage.Reconciliation reconciliation = ModelUsage.reconcile(original, authoritative);
        Scope scope = Scope.of(routed(invocation, servedRoute, invocation.timeout()));
        recordQuietly(new ModelUsageReconciled(scope, original, reconciliation.usage(),
                reconciliation.discrepancy(), clock.instant()));
        return reconciliation;
    }

    private ModelResponse invoke(ModelInvocation invocation, ModelChunkListener listener) {
        long overallDeadline = System.nanoTime() + invocation.timeout().toNanos();
        ModelResiliencePolicy policy = invocation.model().resilience();
        List<ResolvedModelIdentity> identities = new ArrayList<>();
        identities.add(invocation.model().identity());
        identities.addAll(policy.fallbacks());
        GatewayException last = null;
        ResolvedModelIdentity previous = null;
        for (int routeIndex = 0; routeIndex < identities.size(); routeIndex++) {
            ResolvedModelIdentity identity = identities.get(routeIndex);
            if (identity.role() != invocation.model().identity().role()) {
                throw new GatewayException(GatewayErrorCode.CAPABILITY_UNSUPPORTED,
                        "fallback identity does not match the requested model role");
            }
            if (routeIndex > 0) requireCompatibleFallback(invocation, identity, listener != null);
            Duration routeRemaining = remaining(overallDeadline);
            ModelInvocation routed = routed(invocation, identity,
                    routeRemaining.compareTo(policy.requestTimeout()) > 0 ? policy.requestTimeout() : routeRemaining);
            Scope scope = Scope.of(routed);
            if (previous != null) {
                recordQuietly(new ModelFallbackSelected(scope, previous.gatewayId(), previous.providerModel(),
                        clock.instant()));
            }
            for (int attemptNumber = 1; attemptNumber <= policy.maxAttemptsPerRoute(); attemptNumber++) {
                Attempt attempt = new Attempt();
                recordQuietly(new ModelInvocationAttempted(scope, routeIndex, attemptNumber, routeIndex > 0,
                        clock.instant()));
                RouteGuard guard = guard(routed, policy);
                boolean admitted = false;
                try {
                    guard.beforeCall(scope, policy);
                    admitted = guard.acquire(policy.bulkheadWait());
                    if (!admitted) throw GatewayException.notSent(GatewayErrorCode.MODEL_CAPACITY_EXHAUSTED,
                            "model route has no available invocation capacity");
                    ModelResponse response = execute(routed, listener, scope, attempt);
                    guard.success(scope);
                    recordQuietly(new ModelInvocationCompleted(scope, attempt.adapterVersion, response.served(),
                            response.usage(), response.finishReason(), response.chunks(), response.degradations(),
                            clock.instant()));
                    return response;
                } catch (GatewayException failure) {
                    if (attempt.listenerFailure.get() == failure) {
                        recordQuietly(failed(scope, GatewayErrorCode.CANCELLED, attempt, null, routed.request()));
                        throw failure;
                    }
                    GatewayException classified = attempt.sequence.get() > 0 && partialProviderFailure(failure)
                            ? new GatewayException(GatewayErrorCode.PARTIAL_STREAM,
                                    "provider stream ended after content was delivered") : failure;
                    recordQuietly(failed(scope, classified.code(), attempt, failure, routed.request()));
                    guard.failure(scope, classified, policy);
                    last = classified;
                    boolean anotherAttempt = attemptNumber < policy.maxAttemptsPerRoute();
                    boolean anotherRoute = routeIndex + 1 < identities.size();
                    if (!safeToRepeat(failure, attempt) || (!anotherAttempt && !anotherRoute)) throw classified;
                    if (anotherAttempt) backoff(invocation, policy, attemptNumber, failure.retryAfter(), overallDeadline);
                    else break;
                } catch (Error error) {
                    recordQuietly(failed(scope, attempt.listenerFailure.get() == error ? GatewayErrorCode.CANCELLED
                            : GatewayErrorCode.ADAPTER_FAILURE, attempt, null, routed.request()));
                    if (error instanceof VirtualMachineError || attempt.listenerFailure.get() == error) throw error;
                    throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE,
                            "invocation failed with " + error.getClass().getName());
                } catch (RuntimeException unexpected) {
                    if (attempt.listenerFailure.get() == unexpected) {
                        recordQuietly(failed(scope, GatewayErrorCode.CANCELLED, attempt, null, routed.request()));
                        throw unexpected;
                    }
                    if (attempt.degradationSinkFailed) throw unexpected;
                    GatewayException typed = new GatewayException(GatewayErrorCode.ADAPTER_FAILURE,
                            "invocation failed with " + unexpected.getClass().getName());
                    recordQuietly(failed(scope, typed.code(), attempt, null, routed.request()));
                    throw typed;
                } finally {
                    if (admitted) guard.release();
                }
            }
            previous = identity;
        }
        throw Objects.requireNonNull(last, "last failure");
    }

    private ModelInvocation routed(ModelInvocation source, ResolvedModelIdentity identity, Duration remaining) {
        return new ModelInvocation(new PinnedModel(source.model().workspace(), source.model().release(), identity,
                source.model().resilience()), source.identity(), source.correlation(), source.request(), remaining,
                source.cancellation());
    }

    private static void requireCompatibleFallback(ModelInvocation invocation, ResolvedModelIdentity fallback,
            boolean streaming) {
        ResolvedModelIdentity primary = invocation.model().identity();
        for (GatewayCapability capability : needed(invocation.request(), streaming)) {
            if (primary.capabilityPolicy().modeFor(capability) == CapabilityMode.REQUIRED
                    && !fallback.advertisedCapabilities().contains(capability)) {
                throw new GatewayException(GatewayErrorCode.CAPABILITY_UNSUPPORTED,
                        "release-captured fallback lacks required capability " + capability);
            }
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new GatewayException(GatewayErrorCode.TIMEOUT, "model invocation deadline elapsed");
        return Duration.ofNanos(nanos);
    }

    private static boolean safeToRepeat(GatewayException failure, Attempt attempt) {
        return attempt.sequence.get() == 0 && (failure.requestNotSent() || failure.retrySafe());
    }

    private static boolean partialProviderFailure(GatewayException failure) {
        return failure.code() == GatewayErrorCode.PROVIDER_UNAVAILABLE
                || failure.code() == GatewayErrorCode.INVALID_PROVIDER_RESPONSE
                || failure.code() == GatewayErrorCode.TIMEOUT;
    }

    private static void backoff(ModelInvocation invocation, ModelResiliencePolicy policy, int attempt,
            Duration retryAfter, long deadline) {
        long factor = 1L << Math.min(30, attempt - 1);
        long calculated;
        try { calculated = Math.multiplyExact(policy.initialBackoff().toNanos(), factor); }
        catch (ArithmeticException overflow) { calculated = Long.MAX_VALUE; }
        long nanos = Math.min(calculated, policy.maxBackoff().toNanos());
        if (retryAfter != null) nanos = Math.max(nanos, retryAfter.toNanos());
        double spread = nanos * policy.jitter();
        if (spread > 0) nanos = Math.max(0, (long) (nanos + ThreadLocalRandom.current().nextDouble(-spread, spread)));
        nanos = Math.min(nanos, Math.max(0, deadline - System.nanoTime()));
        if (nanos == 0) return;
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayErrorCode.CANCELLED, "interrupted during model retry backoff");
        }
        if (invocation.cancellation() != null) invocation.cancellation().throwIfCancelled();
        remaining(deadline);
    }

    private RouteGuard guard(ModelInvocation invocation, ModelResiliencePolicy policy) {
        ResolvedModelIdentity identity = invocation.model().identity();
        RouteKey key = new RouteKey(invocation.model().workspace().value(), invocation.model().release().value(),
                identity.role(), identity.gatewayId(), identity.providerModel());
        return routes.compute(key, (ignored, existing) -> existing == null || existing.permits != policy.maxConcurrent()
                ? new RouteGuard(policy.maxConcurrent()) : existing);
    }

    /**
     * Failure evidence. {@code providerContacted} reflects whether the adapter marked the request as sent
     * ({@link ChunkSink#requestSent()}), not the failure code: a code that is usually raised before contact can still
     * arise after the request left. It is false only when nothing was sent or the adapter reported that the request
     * never left ({@link GatewayException#requestNotSent()}).
     */
    private ModelInvocationFailed failed(Scope scope, GatewayErrorCode code, Attempt attempt, GatewayException failure,
            ModelRequest request) {
        boolean contacted = attempt.contacted.get() && (failure == null || !failure.requestNotSent());
        ModelUsage usage = attempt.sequence.get() == 0 ? ModelUsage.UNREPORTED
                : estimatePartial(request, attempt.streamed);
        return new ModelInvocationFailed(scope, code, contacted, usage, clock.instant());
    }

    private ModelResponse execute(ModelInvocation invocation, ModelChunkListener listener, Scope scope,
            Attempt attempt) {
        ResolvedModelIdentity pin = invocation.model().identity();
        if (!invocation.identity().workspaceId().equals(invocation.model().workspace().value())) {
            throw new GatewayException(GatewayErrorCode.TENANT_MISMATCH,
                    "the invoking principal's workspace does not own the pinned release");
        }
        ConfiguredGateway gateway = registry.find(pin.gatewayId()).orElseThrow(() -> new GatewayException(
                GatewayErrorCode.GATEWAY_NOT_CONFIGURED, "pinned gateway '" + pin.gatewayId() + "' is not configured"));
        GatewayDescriptor descriptor = gateway.adapter().descriptor();
        if (!descriptor.type().equals(pin.gatewayType()) || !descriptor.protocol().equals(pin.protocol())) {
            throw new GatewayException(GatewayErrorCode.GATEWAY_IDENTITY_CHANGED, "gateway '" + pin.gatewayId()
                    + "' is now " + descriptor.type() + " (" + descriptor.protocol() + ") but the release pinned "
                    + pin.gatewayType() + " (" + pin.protocol() + ")");
        }
        attempt.adapterVersion = descriptor.adapterVersion();
        Set<GatewayCapability> advertised = gateway.adapter().capabilities();
        if (advertised == null) {
            throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE, "adapter advertised no capability set");
        }
        Negotiation negotiation = CapabilityNegotiator.negotiate(needed(invocation.request(), listener != null),
                advertised, pin.capabilityPolicy());
        if (negotiation.degraded(GatewayCapability.TOOL_CALLS) && hasToolHistory(invocation.request())) {
            // Tool definitions can be withheld; earlier tool calls and results cannot be sent without the capability.
            throw new GatewayException(GatewayErrorCode.CAPABILITY_UNSUPPORTED,
                    "required capabilities unsupported by the gateway: TOOL_CALLS (the conversation has tool history)");
        }
        for (DegradedBehavior degradation : negotiation.degradations()) {
            try {
                events.record(new CapabilityDegraded(scope, degradation.capability(), degradation, clock.instant()));
            } catch (RuntimeException sinkFailure) {
                attempt.degradationSinkFailed = true;
                throw sinkFailure;
            }
        }
        if (invocation.cancellation() != null) {
            invocation.cancellation().throwIfCancelled();
        }
        InvocationPlan plan = new InvocationPlan(negotiation.granted(GatewayCapability.STREAMING),
                negotiation.granted(GatewayCapability.TOOL_CALLS),
                negotiation.granted(GatewayCapability.STRUCTURED_OUTPUT), negotiation.granted(GatewayCapability.USAGE),
                negotiation.granted(GatewayCapability.CANCELLATION));
        ModelRequest request = reduce(invocation.request(), plan);
        AdapterResult result = runAdapter(invocation, gateway, request, plan, listener, attempt);
        return respond(pin, negotiation, plan, result, request, listener, attempt, scope);
    }

    /** Capabilities an invocation needs. Usage, cancellation (for the deadline), and metadata are always wanted. */
    static Set<GatewayCapability> needed(ModelRequest request, boolean streaming) {
        EnumSet<GatewayCapability> needed = EnumSet.of(GatewayCapability.USAGE, GatewayCapability.CANCELLATION,
                GatewayCapability.MODEL_METADATA);
        if (streaming) {
            needed.add(GatewayCapability.STREAMING);
        }
        if (!request.tools().isEmpty() || hasToolHistory(request)) {
            needed.add(GatewayCapability.TOOL_CALLS);
        }
        if (request.responseFormat().kind() == ResponseFormat.Kind.JSON_SCHEMA) {
            needed.add(GatewayCapability.STRUCTURED_OUTPUT);
        }
        return needed;
    }

    private static boolean hasToolHistory(ModelRequest request) {
        return request.messages().stream().anyMatch(message ->
                message.role() == ModelMessage.Role.TOOL || !message.toolCalls().isEmpty());
    }

    /** Removes what a degradation withholds from the provider. */
    private static ModelRequest reduce(ModelRequest request, InvocationPlan plan) {
        ModelRequest reduced = request;
        if (!plan.tools() && !reduced.tools().isEmpty()) {
            reduced = reduced.withTools(List.of());
        }
        if (!plan.structuredOutput() && reduced.responseFormat().kind() == ResponseFormat.Kind.JSON_SCHEMA) {
            reduced = reduced.withResponseFormat(ResponseFormat.TEXT);
        }
        return reduced;
    }

    private AdapterResult runAdapter(ModelInvocation invocation, ConfiguredGateway gateway, ModelRequest request,
            InvocationPlan plan, ModelChunkListener listener, Attempt attempt) {
        CancellationSignal signal = CancellationSignal.create();
        CancellationSignal.Registration link = invocation.cancellation() == null ? () -> { }
                : invocation.cancellation().onCancel(() -> signal.cancel(Reason.CALLER));
        long deadline = System.nanoTime() + invocation.timeout().toNanos();
        ChunkSink sink = new ChunkSink() {
            @Override
            public void requestSent() {
                signal.throwIfCancelled();
                attempt.contacted.set(true);
                attempt.activityNanos.set(System.nanoTime());
            }

            @Override
            public void emit(String delta) {
                Objects.requireNonNull(delta, "delta");
                attempt.contacted.set(true);
                attempt.activityNanos.set(System.nanoTime());
                if (!plan.stream()) {
                    throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE,
                            "adapter streamed without the capability");
                }
                signal.throwIfCancelled();
                attempt.delivery.lock();
                try {
                    signal.throwIfCancelled();
                    try {
                        listener.onChunk(new ModelChunk(attempt.sequence.getAndIncrement(), delta));
                    } catch (RuntimeException | Error failure) {
                        if (failure instanceof VirtualMachineError) {
                            throw failure;
                        }
                        attempt.listenerFailure.compareAndSet(null, failure);
                        signal.cancel(Reason.LISTENER_FAILED);
                        throw CancellationSignal.failure(Reason.LISTENER_FAILED);
                    }
                    attempt.streamed.append(delta);
                } finally {
                    attempt.delivery.unlock();
                }
            }
        };
        try (JitGatewayCredential credential = new JitGatewayCredential(gateway.gatewayId(), gateway.credentialRef(),
                credentials, credentialAudit, invocation.identity(), clock, signal)) {
            CompletableFuture<AdapterResult> work = new CompletableFuture<>();
            CancellationSignal.Registration abandon = signal.onCancel(() -> work.cancel(false));
            ResolvedModelIdentity pin = invocation.model().identity();
            AdapterInvocationEvidence evidence = new AdapterInvocationEvidence(Scope.of(invocation), pin.fingerprint(),
                    pin.fingerprint() != null
                            && pin.advertisedCapabilities().contains(GatewayCapability.MODEL_FINGERPRINT));
            AdapterInvocation adapterInvocation = new AdapterInvocation(invocation.model().identity().providerModel(),
                    request, plan, credential, signal, invocation.timeout(), evidence);
            if (signal.isCancelled()) {
                throw stopped(signal, attempt);
            }
            Thread.ofVirtual().name("agentworks-gateway-" + gateway.gatewayId()).start(() -> {
                try {
                    work.complete(gateway.adapter().invoke(adapterInvocation, sink));
                } catch (Throwable failure) {
                    work.completeExceptionally(failure);
                }
            });
            try {
                AdapterResult result = await(work, signal, deadline, gateway, attempt,
                        plan.stream() ? invocation.model().resilience().streamIdleTimeout() : null);
                if (signal.isCancelled()) {
                    throw stopped(signal, attempt);
                }
                attempt.contacted.set(true);
                return result;
            } finally {
                abandon.close();
            }
        } finally {
            link.close();
        }
    }

    private static AdapterResult await(CompletableFuture<AdapterResult> work, CancellationSignal signal, long deadline,
            ConfiguredGateway gateway, Attempt attempt, Duration idleTimeout) {
        while (true) {
            long now = System.nanoTime();
            long wait = Math.max(0, deadline - now);
            if (idleTimeout != null) {
                wait = Math.min(wait, Math.max(0,
                        attempt.activityNanos.get() + idleTimeout.toNanos() - now));
            }
            try {
                AdapterResult result = work.get(wait, TimeUnit.NANOSECONDS);
                if (result == null) {
                    throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE,
                            "adapter " + gateway.adapter().type() + " returned no result");
                }
                return result;
            } catch (TimeoutException elapsed) {
                now = System.nanoTime();
                boolean deadlineElapsed = now >= deadline;
                boolean idleElapsed = idleTimeout != null
                        && now - attempt.activityNanos.get() >= idleTimeout.toNanos();
                if (!deadlineElapsed && !idleElapsed) continue;
                signal.cancel(Reason.DEADLINE);
                throw stopped(signal, attempt);
            } catch (CancellationException abandoned) {
                throw stopped(signal, attempt);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                signal.cancel(Reason.CALLER);
                throw stopped(signal, attempt);
            } catch (ExecutionException failed) {
                if (signal.isCancelled()) {
                    throw stopped(signal, attempt);
                }
                Throwable cause = failed.getCause();
                if (cause instanceof GatewayException typed) {
                    // Rebuilt so that no cause an adapter attached can carry provider text further.
                    throw typed.withoutCause();
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                // The cause is dropped deliberately: its message may carry provider bodies or credential material.
                throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE, "adapter " + gateway.adapter().type()
                        + " failed with " + cause.getClass().getName());
            }
        }
    }

    /**
     * The early-end failure. It first waits, bounded by {@link #LISTENER_QUIESCE}, for an in-progress chunk callback,
     * so none outlives the call; an interrupted caller still waits, and its interrupt status is restored afterwards.
     */
    private static RuntimeException stopped(CancellationSignal signal, Attempt attempt) {
        boolean interrupted = Thread.interrupted();
        long end = System.nanoTime() + LISTENER_QUIESCE.toNanos();
        while (true) {
            try {
                if (attempt.delivery.tryLock(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    attempt.delivery.unlock();
                }
                break;
            } catch (InterruptedException again) {
                interrupted = true;
                if (System.nanoTime() >= end) {
                    break;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Reason reason = signal.reason().orElse(Reason.CALLER);
        Throwable listenerFailure = attempt.listenerFailure.get();
        if (reason == Reason.LISTENER_FAILED && listenerFailure != null) {
            if (listenerFailure instanceof Error error) {
                throw error;
            }
            return (RuntimeException) listenerFailure;
        }
        return CancellationSignal.failure(reason);
    }

    private ModelResponse respond(ResolvedModelIdentity pin, Negotiation negotiation, InvocationPlan plan,
            AdapterResult result, ModelRequest request, ModelChunkListener listener, Attempt attempt, Scope scope) {
        if (!plan.tools() && !result.toolCalls().isEmpty()) {
            throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE, "adapter returned tool calls it was not granted");
        }
        String content = result.content();
        long chunks = attempt.sequence.get();
        if (plan.stream()) {
            if (!content.contentEquals(attempt.streamed)) {
                throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE,
                        "adapter result differs from the chunks it streamed");
            }
        } else if (listener != null && !content.isEmpty()) {
            try {
                listener.onChunk(new ModelChunk(0, content));
            } catch (RuntimeException | Error failure) {
                if (!(failure instanceof VirtualMachineError)) {
                    attempt.listenerFailure.compareAndSet(null, failure);
                }
                throw failure;
            }
            chunks = 1;
        }
        List<DegradedBehavior> degradations = new ArrayList<>(negotiation.degradations());
        ModelUsage usage = plan.usage() && result.usage() != null ? result.usage()
                : estimate(request, result);
        if (plan.usage() && (result.usageMalformed() || result.usage() == null)) {
            // The turn completed, but the granted usage report is missing or cannot be trusted: the captured policy
            // decides, exactly as for a gateway that does not advertise usage at all.
            if (pin.capabilityPolicy().modeFor(GatewayCapability.USAGE) == CapabilityMode.REQUIRED) {
                throw new GatewayException(GatewayErrorCode.INVALID_PROVIDER_RESPONSE, "provider reported "
                        + (result.usageMalformed() ? "malformed" : "no") + " usage and the release requires usage");
            }
            usage = estimate(request, result);
            degradations.add(DegradedBehavior.USAGE_UNREPORTED);
            recordQuietly(new CapabilityDegraded(scope, GatewayCapability.USAGE, DegradedBehavior.USAGE_UNREPORTED,
                    clock.instant()));
        }
        ServedModel served;
        try {
            served = negotiation.granted(GatewayCapability.MODEL_METADATA) && result.servedModel() != null
                    ? new ServedModel(result.servedModel(), result.systemFingerprint(),
                            ServedModel.Source.PROVIDER_REPORTED)
                    : new ServedModel(pin.providerModel(), null, ServedModel.Source.PINNED);
        } catch (IllegalArgumentException invalid) {
            throw new GatewayException(GatewayErrorCode.INVALID_PROVIDER_RESPONSE,
                    "provider reported an invalid served model identity");
        }
        return new ModelResponse(content, result.toolCalls(), result.finishReason(), usage, pin, served,
                degradations, chunks);
    }

    /** Conservative, provider-neutral fallback estimate. Text is counted in memory and never enters evidence. */
    private static ModelUsage estimate(ModelRequest request, AdapterResult result) {
        long inputChars = request.messages().stream().map(ModelMessage::content).filter(Objects::nonNull)
                .mapToLong(String::length).sum();
        long toolChars = request.tools().stream().mapToLong(tool -> tool.name().length()
                + tool.description().length() + tool.parametersJsonSchema().length()).sum();
        toolChars += result.toolCalls().stream().mapToLong(call -> call.name().length()
                + call.argumentsJson().length()).sum();
        return ModelUsage.estimated(tokens(inputChars), tokens(result.content().length()), tokens(toolChars));
    }

    private static ModelUsage estimatePartial(ModelRequest request, CharSequence streamed) {
        long inputChars = request.messages().stream().map(ModelMessage::content).filter(Objects::nonNull)
                .mapToLong(String::length).sum();
        long toolChars = request.tools().stream().mapToLong(tool -> tool.name().length()
                + tool.description().length() + tool.parametersJsonSchema().length()).sum();
        return ModelUsage.estimated(tokens(inputChars), tokens(streamed.length()), tokens(toolChars));
    }

    private static long tokens(long characters) {
        return characters == 0 ? 0 : Math.max(1, (characters + 3) / 4);
    }

    private void recordQuietly(GatewayEvent event) {
        try {
            events.record(event);
        } catch (RuntimeException ignored) {
            // Evidence recording after the outcome must not change the outcome; see GatewayEventSink.
        }
    }

    private record RouteKey(java.util.UUID workspaceId, java.util.UUID releaseId, ModelRole role, String gatewayId,
            String providerModel) { }

    /** Tenant/release/role/provider-isolated bulkhead and closed/open/half-open breaker. */
    private final class RouteGuard {
        private final int permits;
        private final Semaphore bulkhead;
        private int failures;
        private java.time.Instant openUntil;
        private boolean probeInFlight;

        private RouteGuard(int permits) {
            this.permits = permits;
            this.bulkhead = new Semaphore(permits);
        }

        synchronized void beforeCall(Scope scope, ModelResiliencePolicy policy) {
            java.time.Instant now = clock.instant();
            if (openUntil == null) return;
            if (now.isBefore(openUntil)) {
                throw GatewayException.notSent(GatewayErrorCode.MODEL_ROUTE_OPEN, "model route circuit is open");
            }
            if (probeInFlight) {
                throw GatewayException.notSent(GatewayErrorCode.MODEL_ROUTE_OPEN,
                        "model route recovery probe is in progress");
            }
            probeInFlight = true;
            recordQuietly(new ModelRouteStateChanged(scope, "HALF_OPEN", now));
        }

        boolean acquire(Duration wait) {
            try {
                return bulkhead.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new GatewayException(GatewayErrorCode.CANCELLED, "interrupted while awaiting model capacity");
            }
        }

        void release() { bulkhead.release(); }

        synchronized void success(Scope scope) {
            boolean changed = openUntil != null || failures > 0;
            failures = 0;
            openUntil = null;
            probeInFlight = false;
            if (changed) recordQuietly(new ModelRouteStateChanged(scope, "CLOSED", clock.instant()));
        }

        synchronized void failure(Scope scope, GatewayException failure, ModelResiliencePolicy policy) {
            probeInFlight = false;
            if (failure.code() != GatewayErrorCode.PROVIDER_UNAVAILABLE
                    && failure.code() != GatewayErrorCode.PROVIDER_RATE_LIMITED
                    && failure.code() != GatewayErrorCode.TIMEOUT) return;
            failures++;
            if (failures >= policy.breakerFailureThreshold()) {
                openUntil = clock.instant().plus(policy.breakerOpenDuration());
                recordQuietly(new ModelRouteStateChanged(scope, "OPEN", clock.instant()));
            }
        }

        synchronized ModelRouteHealth health(RouteKey key) {
            String state = openUntil == null ? "CLOSED"
                    : clock.instant().isBefore(openUntil) ? "OPEN" : probeInFlight ? "HALF_OPEN" : "PROBE_READY";
            return new ModelRouteHealth(key.workspaceId(), key.releaseId(), key.role(), key.gatewayId(),
                    key.providerModel(), state, permits - bulkhead.availablePermits(), permits, failures);
        }

    }

    /** Mutable per-invocation bookkeeping, confined to one invocation. */
    private static final class Attempt {
        final AtomicBoolean contacted = new AtomicBoolean();
        final AtomicLong sequence = new AtomicLong();
        final AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
        final AtomicLong activityNanos = new AtomicLong(System.nanoTime());
        final ReentrantLock delivery = new ReentrantLock();
        final StringBuilder streamed = new StringBuilder();
        volatile String adapterVersion;
        volatile boolean degradationSinkFailed;
    }
}
