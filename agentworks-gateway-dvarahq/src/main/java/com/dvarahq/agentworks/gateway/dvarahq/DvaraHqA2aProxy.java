package com.dvarahq.agentworks.gateway.dvarahq;

import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialResolutionException;
import com.dvarahq.agentworks.credential.CredentialUseRequest;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.gateway.CancellationSignal;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixed-origin, bounded DvaraHQ A2A JSON-RPC proxy. Dvara AgentWorks remains run/durability authority; this class
 * performs one governed remote hop and neither retries an unknown outcome nor treats process-local health as truth.
 */
public final class DvaraHqA2aProxy {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private final String gatewayId;
    private final URI origin;
    private final CredentialProvider credentials;
    private final int maxRequestBytes;
    private final int maxResponseBytes;
    private final DvaraHqObservationSink observations;
    private final Clock clock;
    private final HttpClient http;
    private final AtomicReference<DvaraHqHealth> health = new AtomicReference<>(DvaraHqHealth.initial());

    public DvaraHqA2aProxy(DvaraHqGatewayConfig config, CredentialProvider credentials,
            int maxRequestBytes, int maxResponseBytes, DvaraHqObservationSink observations) {
        this(config, credentials, maxRequestBytes, maxResponseBytes, observations, Clock.systemUTC());
    }

    DvaraHqA2aProxy(DvaraHqGatewayConfig config, CredentialProvider credentials,
            int maxRequestBytes, int maxResponseBytes, DvaraHqObservationSink observations, Clock clock) {
        Objects.requireNonNull(config, "config");
        this.gatewayId = config.gatewayId();
        this.origin = config.a2aOrigin().orElseThrow(() -> failure(DvaraHqProxyException.Code.NOT_CONFIGURED,
                "DvaraHQ A2A proxy is not configured"));
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        if (maxRequestBytes < 1024 || maxRequestBytes > 1024 * 1024
                || maxResponseBytes < 1024 || maxResponseBytes > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("A2A bounds are outside the supported range");
        }
        this.maxRequestBytes = maxRequestBytes;
        this.maxResponseBytes = maxResponseBytes;
        this.observations = observations == null ? DvaraHqObservationSink.NOOP : observations;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder().connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public DvaraHqHealth health() { return health.get(); }

    public DvaraHqA2aResult exchange(DvaraHqA2aCall call) {
        Objects.requireNonNull(call, "call");
        validateScope(call);
        if (call.cancellation().isCancelled()) {
            throw failure(DvaraHqProxyException.Code.CANCELLED, "DvaraHQ A2A call was cancelled");
        }
        byte[] body = requestBody(call);
        if (body.length > maxRequestBytes) throw failure(DvaraHqProxyException.Code.INVALID_REQUEST,
                "DvaraHQ A2A request exceeds its byte bound");
        try {
            DvaraHqA2aResult result = withCredential(call.gatewayCredential(), primary ->
                    withOptionalCredential(call.delegatedCredential(), delegated -> send(call, body, primary, delegated)));
            healthy("DVARAHQ_A2A_OK");
            emit(DvaraHqObservation.Type.OPERATION_SUCCEEDED, "DVARAHQ_A2A_OK");
            return result;
        } catch (DvaraHqProxyException failure) {
            failed(failure.code().name());
            emit(DvaraHqObservation.Type.OPERATION_FAILED, failure.code().name());
            throw failure;
        } catch (CredentialResolutionException failure) {
            failed("CREDENTIAL_UNAVAILABLE");
            emit(DvaraHqObservation.Type.OPERATION_FAILED, "CREDENTIAL_UNAVAILABLE");
            throw failure(DvaraHqProxyException.Code.CREDENTIAL_UNAVAILABLE,
                    "DvaraHQ A2A credential is unavailable");
        } catch (RuntimeException unexpected) {
            failed("CREDENTIAL_UNAVAILABLE");
            emit(DvaraHqObservation.Type.OPERATION_FAILED, "CREDENTIAL_UNAVAILABLE");
            throw failure(DvaraHqProxyException.Code.CREDENTIAL_UNAVAILABLE,
                    "DvaraHQ A2A credential operation failed");
        }
    }

    private DvaraHqA2aResult send(DvaraHqA2aCall call, byte[] body, char[] primary, char[] delegated) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve("/a2a/" + call.agentId()))
                .timeout(call.timeout()).header("Content-Type", "application/a2a+json")
                .header("Accept", "application/a2a+json, application/json")
                .header("A2A-Version", call.protocolVersion())
                .header("X-Trace-ID", call.context().traceId())
                .header("X-Session-Id", call.context().invocationId())
                .header("Idempotency-Key", call.context().invocationId())
                .header("Authorization", "Bearer " + String.valueOf(primary));
        if (delegated != null) {
            builder.header("X-Dvara-Delegated-Authorization", "Bearer " + String.valueOf(delegated));
        }
        BoundedBodySubscriber bodySubscriber = new BoundedBodySubscriber(maxResponseBytes);
        CompletableFuture<HttpResponse<byte[]>> exchange = http.sendAsync(builder.POST(
                HttpRequest.BodyPublishers.ofByteArray(body)).build(), ignored -> bodySubscriber);
        CancellationSignal.Registration registration = call.cancellation().onCancel(() -> {
            bodySubscriber.cancel();
            exchange.cancel(true);
        });
        try {
            HttpResponse<byte[]> response = exchange.get(call.timeout().toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw httpFailure(response.statusCode());
            }
            return response(call, response.body());
        } catch (TimeoutException elapsed) {
            bodySubscriber.cancel();
            exchange.cancel(true);
            throw failure(DvaraHqProxyException.Code.TIMEOUT, "DvaraHQ A2A call timed out");
        } catch (CancellationException cancelled) {
            throw failure(DvaraHqProxyException.Code.CANCELLED, "DvaraHQ A2A call was cancelled");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            bodySubscriber.cancel();
            exchange.cancel(true);
            throw failure(DvaraHqProxyException.Code.CANCELLED, "DvaraHQ A2A call was interrupted");
        } catch (ExecutionException transport) {
            Throwable cause = transport.getCause();
            if (call.cancellation().isCancelled()) {
                throw failure(DvaraHqProxyException.Code.CANCELLED, "DvaraHQ A2A call was cancelled");
            }
            if (cause instanceof DvaraHqProxyException safe) throw safe;
            if (cause instanceof ResponseLimitExceeded) {
                throw failure(DvaraHqProxyException.Code.RESPONSE_LIMIT,
                        "DvaraHQ A2A response exceeds its byte bound");
            }
            if (cause instanceof HttpTimeoutException) {
                throw failure(DvaraHqProxyException.Code.TIMEOUT, "DvaraHQ A2A call timed out");
            }
            // HTTP parser and TLS failures can include untrusted peer bytes, host details, or other
            // connection material in their messages. Preserve the stable classification only.
            throw failure(DvaraHqProxyException.Code.UNAVAILABLE, "DvaraHQ A2A transport failed");
        } finally {
            registration.close();
        }
    }

    private DvaraHqA2aResult response(DvaraHqA2aCall call, byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                throw failure(DvaraHqProxyException.Code.INVALID_RESPONSE,
                        "DvaraHQ A2A response is not a JSON object");
            }
            JsonNode responseId = root.get("id");
            if (!"2.0".equals(root.path("jsonrpc").stringValue())
                    || responseId == null || !responseId.isString()
                    || !call.rpcId().equals(responseId.stringValue())) {
                throw failure(DvaraHqProxyException.Code.PROTOCOL_MISMATCH,
                        "DvaraHQ A2A response does not match the request");
            }
            if (root.has("error")) {
                throw failure(DvaraHqProxyException.Code.INVALID_RESPONSE,
                        "DvaraHQ A2A peer returned a JSON-RPC error");
            }
            JsonNode result = root.get("result");
            if (result == null || !result.isObject()) {
                throw failure(DvaraHqProxyException.Code.INVALID_RESPONSE,
                        "DvaraHQ A2A response has no result");
            }
            return new DvaraHqA2aResult(call.rpcId(), JSON.writeValueAsString(root), call.context().traceId(),
                    text(result, "id"), text(result, "contextId"), status(result));
        } catch (DvaraHqProxyException safe) {
            throw safe;
        } catch (JacksonException malformed) {
            throw failure(DvaraHqProxyException.Code.INVALID_RESPONSE,
                    "DvaraHQ A2A response is malformed");
        }
    }

    private static String status(JsonNode result) {
        JsonNode status = result.get("status");
        if (status == null) return null;
        if (status.isString()) return status.stringValue();
        return text(status, "state");
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private byte[] requestBody(DvaraHqA2aCall call) {
        try {
            JsonNode params = JSON.readTree(call.paramsJson());
            if (params == null || !params.isObject()) {
                throw failure(DvaraHqProxyException.Code.INVALID_REQUEST, "A2A params must be a JSON object");
            }
            return JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", call.rpcId(),
                    "method", call.method(), "params", params));
        } catch (DvaraHqProxyException safe) {
            throw safe;
        } catch (JacksonException malformed) {
            throw failure(DvaraHqProxyException.Code.INVALID_REQUEST, "A2A params are malformed");
        }
    }

    private void validateScope(DvaraHqA2aCall call) {
        var identity = call.gatewayCredential().identity();
        if (!identity.organizationId().equals(call.context().organizationId())
                || !identity.workspaceId().equals(call.context().workspaceId())) {
            throw failure(DvaraHqProxyException.Code.TENANT_MISMATCH,
                    "DvaraHQ A2A credential scope differs from the invocation");
        }
        call.delegatedCredential().ifPresent(delegated -> {
            if (!delegated.identity().equals(identity)) {
                throw failure(DvaraHqProxyException.Code.TENANT_MISMATCH,
                        "DvaraHQ delegated credential scope differs from the invocation");
            }
        });
    }

    private <T> T withCredential(CredentialUseRequest request, MaterialOperation<T> operation) {
        Instant now = clock.instant();
        CredentialAuthorization authorization = credentials.authorize(request, now);
        try (ResolvedCredential resolved = credentials.resolve(authorization, now)) {
            return resolved.use(clock.instant(), operation::apply);
        }
    }

    private <T> T withOptionalCredential(Optional<CredentialUseRequest> request, MaterialOperation<T> operation) {
        return request.isPresent() ? withCredential(request.get(), operation) : operation.apply(null);
    }

    private static DvaraHqProxyException httpFailure(int status) {
        DvaraHqProxyException.Code code = status == 401 || status == 403
                ? DvaraHqProxyException.Code.AUTHENTICATION_FAILED
                : status == 429 ? DvaraHqProxyException.Code.RATE_LIMITED
                : status == 408 || status >= 500 ? DvaraHqProxyException.Code.UNAVAILABLE
                : DvaraHqProxyException.Code.INVALID_RESPONSE;
        return failure(code, "DvaraHQ A2A endpoint returned HTTP " + status);
    }

    private void healthy(String code) {
        health.set(new DvaraHqHealth(DvaraHqHealth.Status.HEALTHY, code, clock.instant(), 0));
    }

    private void failed(String code) {
        health.updateAndGet(previous -> new DvaraHqHealth(DvaraHqHealth.Status.FAILED, code, clock.instant(),
                previous.consecutiveFailures() + 1));
    }

    private void emit(DvaraHqObservation.Type type, String code) {
        try {
            observations.accept(new DvaraHqObservation(type, gatewayId, "A2A", code,
                    null, null, null, null, clock.instant()));
        } catch (RuntimeException ignored) { }
    }

    private static DvaraHqProxyException failure(DvaraHqProxyException.Code code, String message) {
        return new DvaraHqProxyException(code, message);
    }
    @FunctionalInterface private interface MaterialOperation<T> { T apply(char[] material); }

    /** Completes only after the full body arrives and cancels the HTTP subscription at the byte bound or deadline. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        BoundedBodySubscriber(int limit) {
            this.limit = limit;
            this.bytes = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
        }

        @Override public CompletableFuture<byte[]> getBody() { return result; }

        @Override public void onSubscribe(Flow.Subscription next) {
            if (!subscription.compareAndSet(null, next) || cancelled.get()) {
                next.cancel();
                result.cancel(false);
                return;
            }
            next.request(1);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            Flow.Subscription active = subscription.get();
            if (active == null || cancelled.get() || result.isDone()) return;
            long incoming = items.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incoming > limit - bytes.size()) {
                cancelled.set(true);
                active.cancel();
                result.completeExceptionally(new ResponseLimitExceeded());
                return;
            }
            for (ByteBuffer item : items) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            active.request(1);
        }

        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }

        void cancel() {
            cancelled.set(true);
            Flow.Subscription active = subscription.get();
            if (active != null) active.cancel();
            result.cancel(false);
        }
    }

    private static final class ResponseLimitExceeded extends RuntimeException {
        private ResponseLimitExceeded() { super(null, null, false, false); }
    }

    @Override public String toString() {
        return "DvaraHqA2aProxy[gatewayId=" + gatewayId + ", origin=" + origin + "]";
    }
}
