package com.dvarahq.agentworks.a2a;

import com.dvarahq.agentworks.domain.external.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Server-framework-neutral A2A exposure boundary. HTTP frameworks adapt requests to this single strict contract. */
public final class A2aExposureAdapter {
    public static final String HANDSHAKE_PATH = "/a2a/v1/handshake";
    public static final String SEND_PATH = "/a2a/v1/message:send";
    public static final String CANCEL_PATH = "/a2a/v1/tasks:cancel";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final ExternalExecutorPort executor;
    private final ExternalInteractionHandler interactions;
    private final InboundKeyResolver keys;
    private final Clock clock;
    private final Duration allowedSkew;
    private final int maxPayloadBytes;
    private final ReplayWindow replay;

    public A2aExposureAdapter(ExternalExecutorPort executor, ExternalInteractionHandler interactions,
            InboundKeyResolver keys, Clock clock, Duration allowedSkew, int maxPayloadBytes,
            int replayCapacity) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.allowedSkew = Objects.requireNonNull(allowedSkew, "allowedSkew");
        if (maxPayloadBytes < 1024) throw new IllegalArgumentException("maxPayloadBytes must be at least 1024");
        this.maxPayloadBytes = maxPayloadBytes;
        this.replay = new ReplayWindow(replayCapacity, allowedSkew.multipliedBy(2));
    }

    public A2aOutboundResponse handle(A2aInboundRequest request) {
        try {
            if (!request.method().equals("POST")) return response(405, Map.of("error", "method_not_allowed"));
            if (request.body().length > maxPayloadBytes) throw failure(ExternalProtocolError.PAYLOAD_TOO_LARGE,
                    "inbound A2A payload too large");
            authenticate(request);
            return switch (request.path()) {
                case HANDSHAKE_PATH -> response(200, executor.negotiate(decode(request.body(),
                        ExternalProtocolRequirements.class)));
                case SEND_PATH -> response(200, executor.execute(decode(request.body(),
                        ExternalInvocationRequest.class), interactions));
                case CANCEL_PATH -> {
                    CancelCommand command = decode(request.body(), CancelCommand.class);
                    executor.cancel(command.context(), command.reason());
                    yield response(202, Map.of("status", "cancellation_requested"));
                }
                default -> response(404, Map.of("error", "not_found"));
            };
        } catch (ExternalProtocolException safe) {
            int status = switch (safe.error()) {
                case AUTHENTICATION_FAILED, REPLAY_REJECTED -> 401;
                case PAYLOAD_TOO_LARGE -> 413;
                case TRANSPORT_FAILURE -> 503;
                default -> 422;
            };
            return response(status, Map.of("error", safe.error().name().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException unsafe) {
            return response(400, Map.of("error", "malformed_request"));
        }
    }

    private void authenticate(A2aInboundRequest request) {
        String keyId = header(request, "X-Dvara-Key-Id");
        String timestampText = header(request, "X-Dvara-Timestamp");
        String nonce = header(request, "X-Dvara-Nonce");
        String claimed = header(request, "X-Dvara-Signature");
        Instant timestamp;
        try { timestamp = Instant.parse(timestampText); }
        catch (RuntimeException invalid) { throw failure(ExternalProtocolError.AUTHENTICATION_FAILED, "invalid timestamp"); }
        Instant now = clock.instant();
        if (timestamp.isBefore(now.minus(allowedSkew)) || timestamp.isAfter(now.plus(allowedSkew))) {
            throw failure(ExternalProtocolError.AUTHENTICATION_FAILED, "expired request");
        }
        char[] material = keys.resolve(keyId);
        byte[] key = null;
        try {
            if (material == null || material.length == 0) throw failure(ExternalProtocolError.AUTHENTICATION_FAILED,
                    "unknown key");
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(material));
            key = new byte[bytes.remaining()]; bytes.get(key);
            String canonical = request.method() + "\n" + request.path() + "\n" + timestamp + "\n" + nonce
                    + "\n" + HexFormat.of().formatHex(sha256(request.body()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] supplied;
            try { supplied = Base64.getDecoder().decode(claimed); }
            catch (IllegalArgumentException invalid) { throw failure(ExternalProtocolError.AUTHENTICATION_FAILED,
                    "invalid signature"); }
            if (!MessageDigest.isEqual(expected, supplied)) throw failure(ExternalProtocolError.AUTHENTICATION_FAILED,
                    "invalid signature");
        } catch (ExternalProtocolException safe) {
            throw safe;
        } catch (Exception failure) {
            throw failure(ExternalProtocolError.AUTHENTICATION_FAILED, "verification failed", failure);
        } finally {
            if (material != null) Arrays.fill(material, '\0');
            if (key != null) Arrays.fill(key, (byte) 0);
        }
        if (!replay.claim(keyId + ":" + nonce, now)) {
            throw failure(ExternalProtocolError.REPLAY_REJECTED, "replayed request");
        }
    }

    private static String header(A2aInboundRequest request, String name) {
        String value = request.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (value == null || value.isBlank()) throw failure(ExternalProtocolError.AUTHENTICATION_FAILED,
                "missing authentication header");
        return value;
    }

    private static <T> T decode(byte[] value, Class<T> type) {
        try { return JSON.readValue(value, type); }
        catch (JacksonException failure) { throw failure(ExternalProtocolError.MALFORMED_ENVELOPE,
                "cannot decode A2A envelope", failure); }
    }

    private static A2aOutboundResponse response(int status, Object body) {
        try {
            return new A2aOutboundResponse(status, Map.of("Content-Type", "application/json"),
                    JSON.writeValueAsBytes(body));
        } catch (JacksonException failure) {
            throw new IllegalStateException("cannot encode A2A response", failure);
        }
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static ExternalProtocolException failure(ExternalProtocolError error, String message) {
        return new ExternalProtocolException(error, message);
    }
    private static ExternalProtocolException failure(ExternalProtocolError error, String message, Throwable cause) {
        return new ExternalProtocolException(error, message, cause);
    }

    private record CancelCommand(ExternalInvocationContext context, String reason) { }

    private static final class ReplayWindow {
        private final int capacity;
        private final Duration ttl;
        private final LinkedHashMap<String, Instant> claims = new LinkedHashMap<>();
        private ReplayWindow(int capacity, Duration ttl) {
            if (capacity < 1) throw new IllegalArgumentException("replayCapacity must be positive");
            this.capacity = capacity; this.ttl = ttl;
        }
        private synchronized boolean claim(String key, Instant now) {
            claims.entrySet().removeIf(entry -> entry.getValue().plus(ttl).isBefore(now));
            if (claims.containsKey(key)) return false;
            // Never evict a live nonce: doing so would turn capacity pressure into a replay bypass.
            if (claims.size() == capacity) return false;
            claims.put(key, now);
            return true;
        }
    }
}
