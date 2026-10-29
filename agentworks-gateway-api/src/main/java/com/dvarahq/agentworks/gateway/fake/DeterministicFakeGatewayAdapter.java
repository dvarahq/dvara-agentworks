package com.dvarahq.agentworks.gateway.fake;

import com.dvarahq.agentworks.gateway.AdapterInvocation;
import com.dvarahq.agentworks.gateway.AdapterResult;
import com.dvarahq.agentworks.gateway.AiGatewayAdapter;
import com.dvarahq.agentworks.gateway.ChunkSink;
import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.GatewayDescriptor;
import com.dvarahq.agentworks.gateway.GatewayErrorCode;
import com.dvarahq.agentworks.gateway.GatewayException;
import com.dvarahq.agentworks.gateway.InvocationPlan;
import com.dvarahq.agentworks.gateway.ModelMessage;
import com.dvarahq.agentworks.gateway.ModelRequest;
import com.dvarahq.agentworks.gateway.ModelResolutionRequest;
import com.dvarahq.agentworks.gateway.ModelUsage;
import com.dvarahq.agentworks.gateway.ResolvedModel;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * A deterministic, network-free gateway for tests, local development, and conformance. The same request always yields
 * the same answer: by default an echo of the last user message, streamed word by word, with usage counted in words.
 * It is selected explicitly (type {@value #TYPE}) and records that type in every manifest it resolves, so it can never
 * be mistaken for a real model.
 *
 * <p>It honors cancellation only when it advertises {@link GatewayCapability#CANCELLATION}, which lets tests prove the
 * degraded {@code LOCAL_CANCELLATION_ONLY} path. It keeps a SHA-256 digest of any presented credential, never the
 * material itself.
 */
public final class DeterministicFakeGatewayAdapter implements AiGatewayAdapter {

    public static final String TYPE = "deterministic-fake";
    public static final String PROTOCOL = "agentworks-fake/1";
    /** The longest a {@link FakeReply#hang()} or delay waits when the fake cannot be cancelled. */
    public static final Duration HANG_LIMIT = Duration.ofSeconds(30);

    private final GatewayDescriptor descriptor;
    private final Set<GatewayCapability> capabilities;
    private final Map<String, String> catalog;
    private final boolean requireCredential;
    private final Function<AdapterInvocation, FakeReply> script;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<RecordedRequest> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> lastCredentialDigest = new AtomicReference<>();

    private DeterministicFakeGatewayAdapter(Builder builder) {
        this.descriptor = new GatewayDescriptor(builder.gatewayId, TYPE, "1", PROTOCOL);
        this.capabilities = Collections.unmodifiableSet(builder.capabilities.isEmpty()
                ? EnumSet.noneOf(GatewayCapability.class) : EnumSet.copyOf(builder.capabilities));
        this.catalog = Map.copyOf(builder.catalog);
        this.requireCredential = builder.requireCredential;
        this.script = builder.script;
        catalog.forEach((alias, model) -> ModelResolutionRequest.requireAlias(alias));
    }

    public static Builder builder(String gatewayId) {
        return new Builder(gatewayId);
    }

    @Override
    public GatewayDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public Set<GatewayCapability> capabilities() {
        return capabilities;
    }

    @Override
    public ResolvedModel resolveModel(ModelResolutionRequest request) {
        String model = catalog.get(request.alias());
        if (model == null) {
            throw new GatewayException(GatewayErrorCode.MODEL_ALIAS_UNKNOWN, "alias is not in the fake catalog");
        }
        return new ResolvedModel(model, ResolvedModel.ResolutionSource.GATEWAY_ALIAS_CATALOG, null);
    }

    @Override
    public AdapterResult invoke(AdapterInvocation invocation, ChunkSink sink) {
        // Like a real adapter, the credential is resolved before the request leaves; a resolution failure means the
        // provider never received anything.
        String credentialDigest = invocation.credential().present()
                ? invocation.credential().withMaterial(DeterministicFakeGatewayAdapter::digest) : null;
        sink.requestSent();
        requests.incrementAndGet();
        lastRequest.set(new RecordedRequest(invocation.providerModel(), invocation.request(), invocation.plan()));
        lastCredentialDigest.set(credentialDigest);
        if (credentialDigest == null && requireCredential) {
            throw new GatewayException(GatewayErrorCode.PROVIDER_AUTHENTICATION_FAILED, "fake provider requires a key");
        }
        FakeReply reply = Objects.requireNonNull(script.apply(invocation), "script reply");
        boolean cancellable = invocation.plan().cancellation() && capabilities.contains(GatewayCapability.CANCELLATION);
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var registration = invocation.cancellation().onCancel(cancelled::countDown)) {
            if (reply.hang()) {
                pause(HANG_LIMIT, cancellable, cancelled);
                invocation.cancellation().throwIfCancelled();
                throw new GatewayException(GatewayErrorCode.PROVIDER_UNAVAILABLE, "fake provider hung");
            }
            if (reply.failure() != null) {
                throw new GatewayException(reply.failure(), "fake provider failure");
            }
            if (invocation.plan().stream()) {
                for (String chunk : reply.chunks()) {
                    pause(reply.chunkDelay(), cancellable, cancelled);
                    if (cancellable) {
                        invocation.cancellation().throwIfCancelled();
                    }
                    sink.emit(chunk);
                }
            } else {
                for (int index = 0; index < reply.chunks().size(); index++) {
                    pause(reply.chunkDelay(), cancellable, cancelled);
                }
            }
        }
        boolean metadata = capabilities.contains(GatewayCapability.MODEL_METADATA);
        return new AdapterResult(reply.content(), invocation.plan().tools() ? reply.toolCalls() : List.of(),
                reply.finishReason(), invocation.plan().usage() ? reply.usage() : null,
                metadata ? (reply.servedModel() == null ? invocation.providerModel() : reply.servedModel()) : null,
                metadata ? reply.systemFingerprint() : null);
    }

    /** Requests the fake has received (including failed ones). */
    public int requests() {
        return requests.get();
    }

    /** What the fake provider last received: the pinned model, the reduced request, and the plan; never a credential. */
    public record RecordedRequest(String providerModel, ModelRequest request, InvocationPlan plan) {
    }

    public RecordedRequest lastRequest() {
        return lastRequest.get();
    }

    /** SHA-256 hex digest of the last presented credential, or {@code null}. */
    public String lastCredentialDigest() {
        return lastCredentialDigest.get();
    }

    /** SHA-256 hex digest of credential material, computed without creating a String copy of it. */
    public static String digest(char[] material) {
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(material));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(bytes.duplicate());
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            if (bytes.hasArray()) {
                Arrays.fill(bytes.array(), (byte) 0);
            }
        }
    }

    /** The default deterministic answer: an echo of the last user message, one chunk per word. */
    public static FakeReply echo(AdapterInvocation invocation) {
        String last = invocation.request().messages().stream()
                .filter(message -> message.role() == ModelMessage.Role.USER)
                .reduce((first, second) -> second).map(ModelMessage::content).orElse("");
        String content = "echo(" + invocation.providerModel() + "): " + last;
        List<String> chunks = new ArrayList<>();
        int start = 0;
        for (int index = 1; index <= content.length(); index++) {
            if (index == content.length() || content.charAt(index) == ' ') {
                chunks.add(content.substring(start, index));
                start = index;
            }
        }
        long input = invocation.request().messages().stream().map(ModelMessage::content).filter(Objects::nonNull)
                .mapToLong(text -> text.isBlank() ? 0 : text.trim().split("\\s+").length).sum();
        return FakeReply.text(chunks, ModelUsage.reported(input, chunks.size(), null, null, null));
    }

    private static void pause(Duration duration, boolean cancellable, CountDownLatch cancelled) {
        if (duration.isZero()) {
            return;
        }
        Duration bounded = duration.compareTo(HANG_LIMIT) > 0 ? HANG_LIMIT : duration;
        try {
            if (cancellable) {
                cancelled.await(bounded.toNanos(), TimeUnit.NANOSECONDS);
            } else {
                Thread.sleep(bounded);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GatewayException(GatewayErrorCode.CANCELLED, "fake provider interrupted");
        }
    }

    @Override
    public String toString() {
        return "DeterministicFakeGatewayAdapter[" + descriptor.gatewayId() + "]";
    }

    public static final class Builder {
        private final String gatewayId;
        private final Map<String, String> catalog = new LinkedHashMap<>();
        private Set<GatewayCapability> capabilities = GatewayCapability.negotiatedCapabilities();
        private boolean requireCredential;
        private Function<AdapterInvocation, FakeReply> script = DeterministicFakeGatewayAdapter::echo;

        private Builder(String gatewayId) {
            this.gatewayId = Objects.requireNonNull(gatewayId, "gatewayId");
        }

        public Builder model(String alias, String providerModel) {
            catalog.put(alias, providerModel);
            return this;
        }

        public Builder models(Map<String, String> aliases) {
            catalog.putAll(aliases);
            return this;
        }

        public Builder capabilities(Set<GatewayCapability> advertised) {
            this.capabilities = EnumSet.noneOf(GatewayCapability.class);
            this.capabilities.addAll(advertised);
            return this;
        }

        /** Makes the fake reject invocations without a presented credential, like a real provider. */
        public Builder requireCredential(boolean required) {
            this.requireCredential = required;
            return this;
        }

        public Builder script(Function<AdapterInvocation, FakeReply> reply) {
            this.script = Objects.requireNonNull(reply, "reply");
            return this;
        }

        public DeterministicFakeGatewayAdapter build() {
            return new DeterministicFakeGatewayAdapter(this);
        }
    }
}
