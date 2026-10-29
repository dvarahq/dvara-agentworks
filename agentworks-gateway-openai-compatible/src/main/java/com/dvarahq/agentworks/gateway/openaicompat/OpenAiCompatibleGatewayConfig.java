package com.dvarahq.agentworks.gateway.openaicompat;

import com.dvarahq.agentworks.gateway.GatewayCapability;
import com.dvarahq.agentworks.gateway.ModelResolutionRequest;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Operator configuration of one OpenAI-compatible gateway. It holds no credential: the credential reference lives in
 * the Core gateway configuration and is resolved just in time per invocation.
 *
 * @param baseUri the API base, for example {@code https://api.openai.com/v1} or {@code http://127.0.0.1:8000/v1}; the
 *        adapter posts to {@code <baseUri>/chat/completions}. HTTPS is required except for loopback hosts, and the URI
 *        may not carry user information, a query, or a fragment, so no secret can hide in it.
 * @param models the alias catalog: logical alias to exact provider model identifier
 * @param capabilities what this endpoint supports; restrict it for servers without, for example, tools or usage
 * @param maxResponseBytes the most bytes read for a blocking response, and the most characters a stream may retain
 *        (content plus tool-call ids, names, and arguments)
 * @param maxStreamBytes the most wire bytes one streamed response may carry, SSE and JSON framing included; it must be
 *        positive, or exactly {@link #UNSET} ({@code -1}), and then {@link #effectiveMaxStreamBytes()} derives it from
 *        {@code maxResponseBytes}. An explicit 0 is rejected rather than read as "unset".
 * @param outputTokenField the request field that carries the output-token bound; {@code null} selects the default:
 *        {@code max_completion_tokens} for {@code api.openai.com}, whose reasoning models reject {@code max_tokens},
 *        and {@code max_tokens} elsewhere, because compatible servers (vLLM, llama.cpp, Ollama, LiteLLM) accept it
 *        and not all of them recognize {@code max_completion_tokens}
 */
public record OpenAiCompatibleGatewayConfig(String gatewayId, URI baseUri, Map<String, String> models,
        Set<GatewayCapability> capabilities, Duration connectTimeout, long maxResponseBytes, long maxStreamBytes,
        OutputTokenField outputTokenField) {

    /** The Chat Completions request field for the output-token bound. */
    public enum OutputTokenField {
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens");

        private final String wireName;

        OutputTokenField(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024 * 1024;
    /**
     * The floor of the derived stream cap: when {@code maxStreamBytes} is {@link #UNSET}, the cap is the larger of this
     * and {@code 8 * maxResponseBytes} ({@link #effectiveMaxStreamBytes()}). Each streamed delta carries a few hundred
     * bytes of framing, so the wire bound is well above the content bound.
     */
    public static final long DEFAULT_MAX_STREAM_BYTES = 64L * 1024 * 1024;
    /**
     * {@code maxStreamBytes} value meaning "not set": derive it from {@code maxResponseBytes}. It is negative so that an
     * explicit {@code 0} (for example {@code max-stream-bytes=0B}) cannot be mistaken for "unset" and is rejected.
     */
    public static final long UNSET = -1;

    public OpenAiCompatibleGatewayConfig {
        Objects.requireNonNull(gatewayId, "gatewayId");
        baseUri = validated(Objects.requireNonNull(baseUri, "baseUri"));
        Objects.requireNonNull(models, "models");
        models.forEach((alias, model) -> {
            ModelResolutionRequest.requireAlias(alias);
            if (model == null || model.isBlank() || model.length() > 256) {
                throw new IllegalArgumentException("provider model for alias " + alias + " must be 1 to 256 characters");
            }
        });
        models = Collections.unmodifiableMap(new TreeMap<>(models));
        capabilities = capabilities == null ? GatewayCapability.negotiatedCapabilities()
                : (capabilities.isEmpty() ? EnumSet.noneOf(GatewayCapability.class) : EnumSet.copyOf(capabilities));
        for (GatewayCapability capability : capabilities) {
            if (!capability.negotiated()) {
                throw new IllegalArgumentException("the OpenAI-compatible adapter cannot advertise " + capability);
            }
        }
        capabilities = Collections.unmodifiableSet(capabilities);
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        if (maxStreamBytes != UNSET && maxStreamBytes <= 0) {
            throw new IllegalArgumentException("maxStreamBytes must be positive");
        }
        if (outputTokenField == null) {
            outputTokenField = "api.openai.com".equalsIgnoreCase(baseUri.getHost())
                    ? OutputTokenField.MAX_COMPLETION_TOKENS : OutputTokenField.MAX_TOKENS;
        }
    }

    public static OpenAiCompatibleGatewayConfig of(String gatewayId, URI baseUri, Map<String, String> models) {
        return new OpenAiCompatibleGatewayConfig(gatewayId, baseUri, models, null, null, DEFAULT_MAX_RESPONSE_BYTES,
                UNSET, null);
    }

    public OpenAiCompatibleGatewayConfig withCapabilities(Set<GatewayCapability> advertised) {
        return new OpenAiCompatibleGatewayConfig(gatewayId, baseUri, models, advertised, connectTimeout, maxResponseBytes,
                maxStreamBytes, outputTokenField);
    }

    public OpenAiCompatibleGatewayConfig withMaxResponseBytes(long bytes) {
        return new OpenAiCompatibleGatewayConfig(gatewayId, baseUri, models, capabilities, connectTimeout, bytes,
                maxStreamBytes, outputTokenField);
    }

    public OpenAiCompatibleGatewayConfig withMaxStreamBytes(long bytes) {
        return new OpenAiCompatibleGatewayConfig(gatewayId, baseUri, models, capabilities, connectTimeout,
                maxResponseBytes, bytes, outputTokenField);
    }

    public OpenAiCompatibleGatewayConfig withOutputTokenField(OutputTokenField field) {
        return new OpenAiCompatibleGatewayConfig(gatewayId, baseUri, models, capabilities, connectTimeout,
                maxResponseBytes, maxStreamBytes, field);
    }

    /**
     * The stream wire cap in force: the explicit {@code maxStreamBytes}, or, when unset,
     * {@code max(DEFAULT_MAX_STREAM_BYTES, 8 * maxResponseBytes)} so that raising the content bound alone never fails.
     */
    public long effectiveMaxStreamBytes() {
        if (maxStreamBytes != UNSET) {
            return maxStreamBytes;
        }
        long derived = maxResponseBytes > Long.MAX_VALUE / 8 ? Long.MAX_VALUE : maxResponseBytes * 8;
        return Math.max(DEFAULT_MAX_STREAM_BYTES, derived);
    }

    /**
     * Cross-field validation. The adapter runs it when it is built, so the withers can be applied in any order; any
     * other holder of a configuration can run it too. Only an explicitly set stream cap is checked against the content
     * bound.
     *
     * @return this configuration
     * @throws IllegalArgumentException when {@code maxStreamBytes} is set below {@code maxResponseBytes}
     */
    public OpenAiCompatibleGatewayConfig validate() {
        if (maxStreamBytes != UNSET && maxStreamBytes < maxResponseBytes) {
            // SSE framing makes a stream's wire bytes exceed its content; raise maxStreamBytes with maxResponseBytes.
            throw new IllegalArgumentException("maxStreamBytes must be at least maxResponseBytes");
        }
        return this;
    }

    /** The chat completions endpoint. */
    public URI endpoint() {
        String base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/chat/completions");
    }

    private static URI validated(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.isOpaque()) {
            throw new IllegalArgumentException("baseUri must be an absolute http(s) URI with a host");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUri must not carry user information, a query, or a fragment");
        }
        if (scheme.equals("http")) {
            if (!loopback(uri.getHost())) {
                throw new IllegalArgumentException("plain http is allowed only for loopback hosts; use https");
            }
        } else if (!scheme.equals("https")) {
            throw new IllegalArgumentException("baseUri scheme must be https (or http for loopback hosts)");
        }
        return uri;
    }

    private static boolean loopback(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (bare.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!bare.matches("[0-9.]+") && !bare.contains(":")) {
            return false;
        }
        try {
            // Only literal addresses reach here, so no DNS lookup happens.
            return InetAddress.getByName(bare).isLoopbackAddress();
        } catch (UnknownHostException invalid) {
            return false;
        }
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleGatewayConfig[gatewayId=" + gatewayId + ", endpoint=" + endpoint() + ", models="
                + models.keySet() + ", capabilities=" + capabilities + "]";
    }
}
