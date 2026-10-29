package com.dvarahq.agentworks.gateway;

import java.util.Objects;
import java.time.Duration;

/**
 * A typed gateway failure. Messages are written by Core or adapters from identifiers only; they never contain prompt
 * content, provider response bodies, or credential material, and adapters must not attach causes whose messages could.
 */
public final class GatewayException extends RuntimeException {
    private final GatewayErrorCode code;
    private final String detail;
    private final boolean requestNotSent;
    private final boolean retrySafe;
    private final Duration retryAfter;

    public GatewayException(GatewayErrorCode code, String message) {
        this(code, message, false, false, null);
    }

    private GatewayException(GatewayErrorCode code, String message, boolean requestNotSent, boolean retrySafe,
            Duration retryAfter) {
        super(Objects.requireNonNull(code, "code").name() + ": " + Objects.requireNonNull(message, "message"));
        this.code = code;
        this.detail = message;
        this.requestNotSent = requestNotSent;
        this.retrySafe = retrySafe;
        this.retryAfter = retryAfter;
    }

    /**
     * A failure the adapter knows happened before its request could reach the provider even though it had already
     * called {@code ChunkSink.requestSent()}, for example a connection that never opened. Failure evidence then reports
     * {@code providerContacted=false}.
     */
    public static GatewayException notSent(GatewayErrorCode code, String message) {
        return new GatewayException(code, message, true, true, null);
    }

    /** Provider explicitly confirmed that no model result was produced, so a bounded retry cannot duplicate a turn. */
    public static GatewayException retryable(GatewayErrorCode code, String message, Duration retryAfter) {
        if (retryAfter != null && retryAfter.isNegative()) {
            retryAfter = Duration.ZERO;
        }
        if (retryAfter != null && retryAfter.compareTo(Duration.ofMinutes(5)) > 0) {
            retryAfter = Duration.ofMinutes(5);
        }
        return new GatewayException(code, message, false, true, retryAfter);
    }

    /** The same failure without any attached cause, preserving the code, detail, and send state. */
    public GatewayException withoutCause() {
        return new GatewayException(code, detail, requestNotSent, retrySafe, retryAfter);
    }

    public boolean requestNotSent() {
        return requestNotSent;
    }

    public boolean retrySafe() { return retrySafe; }

    public Duration retryAfter() { return retryAfter; }

    public GatewayErrorCode code() {
        return code;
    }

    /** The message without the code prefix, for APIs that render the code separately. */
    public String detail() {
        return detail;
    }
}
