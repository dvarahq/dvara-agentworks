package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.gateway.*;
import com.dvarahq.agentworks.gateway.openaicompat.OpenAiCompatibleGatewayAdapter;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Optional DvaraHQ model adapter over the provider-neutral Core gateway SPI. */
public final class DvaraHqGatewayAdapter implements AiGatewayAdapter {
    public static final String TYPE = "dvarahq";
    public static final String PROTOCOL = "dvarahq-openai-chat-completions/v1";
    public static final String ADAPTER_VERSION = "1";

    private final DvaraHqGatewayConfig config;
    private final OpenAiCompatibleGatewayAdapter delegate;
    private final GatewayDescriptor descriptor;
    private final DvaraHqObservationSink observations;
    private final Clock clock;
    private final Map<ContinuityKey, String> fingerprints = new ConcurrentHashMap<>();
    private final AtomicReference<DvaraHqHealth> health = new AtomicReference<>(DvaraHqHealth.initial());

    public DvaraHqGatewayAdapter(DvaraHqGatewayConfig config) {
        this(config, DvaraHqObservationSink.NOOP, Clock.systemUTC());
    }

    /** Installs a production observation integration; the sink receives content-free release/run-scoped evidence. */
    public DvaraHqGatewayAdapter(DvaraHqGatewayConfig config, DvaraHqObservationSink observations) {
        this(config, observations, Clock.systemUTC());
    }

    DvaraHqGatewayAdapter(DvaraHqGatewayConfig config, DvaraHqObservationSink observations, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.delegate = new OpenAiCompatibleGatewayAdapter(config.directConfig());
        this.descriptor = new GatewayDescriptor(config.gatewayId(), TYPE, ADAPTER_VERSION, PROTOCOL);
        this.observations = observations == null ? DvaraHqObservationSink.NOOP : observations;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public GatewayDescriptor descriptor() { return descriptor; }
    @Override public Set<GatewayCapability> capabilities() { return config.advertisedCapabilities(); }
    public DvaraHqHealth health() { return health.get(); }

    @Override
    public ResolvedModel resolveModel(ModelResolutionRequest request) {
        DvaraHqGatewayConfig.ModelRoute route = config.models().get(request.alias());
        if (route == null) {
            throw new GatewayException(GatewayErrorCode.MODEL_ALIAS_UNKNOWN,
                    "alias is not in the catalog of gateway '" + config.gatewayId() + "'");
        }
        return new ResolvedModel(route.providerModel(), ResolvedModel.ResolutionSource.GATEWAY_ALIAS_CATALOG,
                route.fingerprint());
    }

    @Override
    public AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink) {
        try {
            AdapterResult result = delegate.invoke(invocation, sink);
            verifyFingerprint(invocation, result);
            health.set(new DvaraHqHealth(DvaraHqHealth.Status.HEALTHY, "DVARAHQ_OK", clock.instant(), 0));
            emit(new DvaraHqObservation(DvaraHqObservation.Type.OPERATION_SUCCEEDED, config.gatewayId(), "MODEL",
                    "DVARAHQ_OK", scope(invocation), invocation.providerModel(), result.servedModel(),
                    result.systemFingerprint(), clock.instant()));
            return result;
        } catch (GatewayException failure) {
            health.updateAndGet(previous -> new DvaraHqHealth(DvaraHqHealth.Status.FAILED, failure.code().name(),
                    clock.instant(), previous.consecutiveFailures() + 1));
            emit(new DvaraHqObservation(DvaraHqObservation.Type.OPERATION_FAILED, config.gatewayId(), "MODEL",
                    failure.code().name(), scope(invocation), invocation.providerModel(), null, null, clock.instant()));
            throw failure;
        }
    }

    private void verifyFingerprint(AdapterInvocation invocation, AdapterResult result) {
        AdapterInvocationEvidence evidence = invocation.evidence();
        String actual = result.systemFingerprint();
        if (evidence != null && evidence.fingerprintMatchRequired()
                && !Objects.equals(evidence.expectedFingerprint(), actual)) {
            throw new GatewayException(GatewayErrorCode.INVALID_PROVIDER_RESPONSE,
                    "DvaraHQ served a model fingerprint different from the release pin");
        }
        if (!capabilities().contains(GatewayCapability.CONTINUITY_EVENTS) || actual == null) return;
        if (evidence == null) {
            throw new GatewayException(GatewayErrorCode.ADAPTER_FAILURE,
                    "DvaraHQ continuity observations require release-scoped invocation evidence");
        }
        ContinuityKey key = ContinuityKey.of(evidence.scope());
        String previous = fingerprints.put(key, actual);
        DvaraHqObservation.Type type = previous == null || previous.equals(actual)
                ? DvaraHqObservation.Type.MODEL_CONTINUITY_OBSERVED
                : DvaraHqObservation.Type.MODEL_CONTINUITY_CHANGED;
        emit(new DvaraHqObservation(type, config.gatewayId(), "MODEL", type.name(), evidence.scope(),
                invocation.providerModel(), result.servedModel(), actual, clock.instant()));
    }

    private static GatewayEvent.Scope scope(AdapterInvocation invocation) {
        return invocation.evidence() == null ? null : invocation.evidence().scope();
    }

    private record ContinuityKey(java.util.UUID workspaceId, java.util.UUID releaseId, ModelRole role,
            String gatewayId, String providerModel) {
        static ContinuityKey of(GatewayEvent.Scope scope) {
            return new ContinuityKey(scope.workspaceId(), scope.releaseId(), scope.role(), scope.gatewayId(),
                    scope.providerModel());
        }
    }

    private void emit(DvaraHqObservation observation) {
        try { observations.accept(observation); } catch (RuntimeException ignored) { }
    }

    @Override public String toString() {
        return "DvaraHqGatewayAdapter[gatewayId=" + config.gatewayId() + ", capabilities=" + capabilities() + "]";
    }
}
