package com.dvarahq.agentworks.a2a;

import com.dvarahq.agentworks.domain.external.*;
import com.dvarahq.agentworks.runplane.compiled.SharedResourceRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fixed-origin remote executor adapter. Negotiation is mandatory and never cached across an invocation, so
 * capability, assurance, and protocol drift fail before execution. Redirects and ambient proxy/auth state are absent.
 */
public final class RemoteExecutorHttpClient implements ExternalExecutorPort, AutoCloseable {
    public static final String PROTOCOL_VERSION = "dvara-agentworks-executor/v1";
    public static final String SCHEMA_VERSION = "2026-10-14";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final URI origin;
    private final String keyId;
    private final RemoteExecutorCredentials credentials;
    private final HttpClient http;
    private final SharedResourceRegistry.Lease<HttpClient> httpLease;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    private boolean closed;
    private final Clock clock;
    private final int maxPayloadBytes;
    private final int maxEvents;
    private final Duration timeout;

    public RemoteExecutorHttpClient(URI origin, String keyId, RemoteExecutorCredentials credentials,
            int maxPayloadBytes, int maxEvents, Duration timeout) {
        this(origin, keyId, credentials, maxPayloadBytes, maxEvents, timeout, Clock.systemUTC(), false, null, null, null);
    }

    /** Uses a workspace-isolated shared connection pool without putting credentials in the resource key. */
    public RemoteExecutorHttpClient(URI origin, String keyId, RemoteExecutorCredentials credentials,
            int maxPayloadBytes, int maxEvents, Duration timeout, UUID workspaceId,
            SharedResourceRegistry resources, Duration idleTtl) {
        this(origin, keyId, credentials, maxPayloadBytes, maxEvents, timeout, Clock.systemUTC(), false,
                workspaceId, resources, idleTtl);
    }

    RemoteExecutorHttpClient(URI origin, String keyId, RemoteExecutorCredentials credentials,
            int maxPayloadBytes, int maxEvents, Duration timeout, Clock clock, boolean allowLoopbackHttp) {
        this(origin, keyId, credentials, maxPayloadBytes, maxEvents, timeout, clock, allowLoopbackHttp, null, null, null);
    }

    private RemoteExecutorHttpClient(URI origin, String keyId, RemoteExecutorCredentials credentials,
            int maxPayloadBytes, int maxEvents, Duration timeout, Clock clock, boolean allowLoopbackHttp,
            UUID workspaceId, SharedResourceRegistry resources, Duration idleTtl) {
        this.origin = validateOrigin(origin, allowLoopbackHttp);
        this.keyId = required(keyId, "keyId");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        if (maxPayloadBytes < 1024) throw new IllegalArgumentException("maxPayloadBytes must be at least 1024");
        if (maxEvents < 1) throw new IllegalArgumentException("maxEvents must be positive");
        this.maxPayloadBytes = maxPayloadBytes;
        this.maxEvents = maxEvents;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (resources == null) {
            this.httpLease = null;
            this.http = http(timeout);
        } else {
            Objects.requireNonNull(workspaceId, "workspaceId"); Objects.requireNonNull(idleTtl, "idleTtl");
            String identity = this.origin.getScheme() + "://" + this.origin.getAuthority();
            String configuration = "sha256:" + hex(sha256(("connectTimeout=" + timeout + "|redirects=NEVER")
                    .getBytes(StandardCharsets.UTF_8)));
            var resourceKey = new SharedResourceRegistry.Key(SharedResourceRegistry.Kind.EXECUTOR, "remote-executor",
                    SharedResourceRegistry.Isolation.WORKSPACE, workspaceId, identity, configuration);
            this.httpLease = resources.acquire(resourceKey, HttpClient.class, idleTtl, () -> http(timeout));
            this.http = httpLease.value();
        }
    }

    static RemoteExecutorHttpClient loopback(URI origin, String keyId, RemoteExecutorCredentials credentials,
            int maxPayloadBytes, int maxEvents, Duration timeout, Clock clock) {
        return new RemoteExecutorHttpClient(origin, keyId, credentials, maxPayloadBytes, maxEvents, timeout, clock, true);
    }

    static RemoteExecutorHttpClient loopback(URI origin, String keyId, RemoteExecutorCredentials credentials,
            int maxPayloadBytes, int maxEvents, Duration timeout, Clock clock, UUID workspaceId,
            SharedResourceRegistry resources, Duration idleTtl) {
        return new RemoteExecutorHttpClient(origin, keyId, credentials, maxPayloadBytes, maxEvents, timeout, clock,
                true, workspaceId, resources, idleTtl);
    }

    private static HttpClient http(Duration timeout) {
        return HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    HttpClient pooledHttpClient() { return http; }

    @Override public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            if (httpLease != null) httpLease.close(); else http.close();
        } finally { lifecycle.writeLock().unlock(); }
    }

    @Override
    public ExternalExecutorDescriptor negotiate(ExternalProtocolRequirements requirements) {
        beginOperation();
        try {
            Objects.requireNonNull(requirements, "requirements");
            ExternalExecutorDescriptor descriptor = decode(request("POST", "/v1/executors/handshake",
                    encode(requirements), "application/json"), ExternalExecutorDescriptor.class);
            validateNegotiation(requirements, descriptor);
            return descriptor;
        } finally { endOperation(); }
    }

    @Override
    public ExternalInvocationResult execute(ExternalInvocationRequest request,
            ExternalInteractionHandler interactions) {
        beginOperation();
        try {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(interactions, "interactions");
            ExternalExecutorDescriptor descriptor = negotiate(request.requirements());
            if (!descriptor.equals(request.expectedExecutor())) {
                throw failure(ExternalProtocolError.NEGOTIATION_MISMATCH,
                        "executor identity or provenance differs from the pinned release");
            }
            HttpResponse<InputStream> response = send("POST", "/v1/executions", encode(request), "text/event-stream");
            requireStatus(response, 200);
            FutureTask<List<ExternalEventEnvelope>> consumption = new FutureTask<>(
                    () -> consume(request, interactions, response.body()));
            Thread worker = Thread.ofVirtual().name("agentworks-external-" + request.context().invocationId())
                    .start(consumption);
            try {
                return new ExternalInvocationResult(descriptor, consumption.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
            } catch (TimeoutException expired) {
                close(response.body()); consumption.cancel(true); worker.interrupt();
                requestCancellation(request.context(), "timeout");
                throw failure(ExternalProtocolError.CANCELLATION_UNCONFIRMED,
                        "executor timed out; cancellation was requested but no terminal event was observed", expired);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt(); close(response.body()); consumption.cancel(true); worker.interrupt();
                requestCancellation(request.context(), "caller");
                throw failure(ExternalProtocolError.CANCELLATION_UNCONFIRMED,
                        "executor interrupted; cancellation was requested but no terminal event was observed", stopped);
            } catch (ExecutionException wrapped) {
                if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
                throw failure(ExternalProtocolError.TRANSPORT_FAILURE, "executor event stream failed", wrapped.getCause());
            }
        } finally { endOperation(); }
    }

    private List<ExternalEventEnvelope> consume(ExternalInvocationRequest request,
            ExternalInteractionHandler interactions, InputStream body) {
        List<ExternalEventEnvelope> events = new ArrayList<>();
        ExternalEventValidator validator = new ExternalEventValidator(request.context(), request.requirements());
        try (InputStream limited = new BoundedInputStream(body, maxPayloadBytes);
                BufferedReader reader = new BufferedReader(new InputStreamReader(limited, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) throw failure(ExternalProtocolError.MALFORMED_ENVELOPE, "invalid SSE field");
                if (events.size() == maxEvents) throw failure(ExternalProtocolError.PAYLOAD_TOO_LARGE, "event limit exceeded");
                ExternalEventEnvelope event = decode(line.substring(5).stripLeading(), ExternalEventEnvelope.class);
                validator.accept(event);
                events.add(event);
                mediate(request.context(), event, interactions);
            }
        } catch (ExternalProtocolException safe) {
            throw safe;
        } catch (IOException failure) {
            throw failure(ExternalProtocolError.TRANSPORT_FAILURE, "executor event stream failed", failure);
        }
        if (!validator.terminal()) {
            throw failure(ExternalProtocolError.MALFORMED_ENVELOPE, "executor stream ended without terminal event");
        }
        return List.copyOf(events);
    }

    @Override
    public void cancel(ExternalInvocationContext context, String reason) {
        beginOperation();
        try {
            Objects.requireNonNull(context, "context");
            requestCancellation(context, reason);
        } finally { endOperation(); }
    }

    private void beginOperation() {
        lifecycle.readLock().lock();
        if (closed) {
            lifecycle.readLock().unlock();
            throw failure(ExternalProtocolError.TRANSPORT_FAILURE, "executor client is closed");
        }
    }

    private void endOperation() { lifecycle.readLock().unlock(); }

    private void requestCancellation(ExternalInvocationContext context, String reason) {
        byte[] body = encode(Map.of("context", context, "reason", required(reason, "reason")));
        HttpResponse<InputStream> response = send("POST", "/v1/executions/" + path(context.invocationId())
                + "/cancel", body, "application/json");
        requireStatus(response, 202);
        close(response.body());
        // A 202 is only acknowledgement of the request. Cancellation is authoritative only on a fenced terminal event.
    }

    private void mediate(ExternalInvocationContext context, ExternalEventEnvelope event,
            ExternalInteractionHandler interactions) {
        String result;
        String kind;
        if (event.type().equals("TOOL_REQUESTED")) {
            kind = "tool_result";
            result = interactions.invokeTool(event.payload());
        } else if (event.type().equals("ARTIFACT_TRANSFER_REQUESTED")) {
            kind = "artifact_reference";
            result = interactions.transferArtifact(event.payload());
        } else {
            return;
        }
        byte[] body = encode(Map.of("context", context, "eventId", event.eventId(), "type", kind,
                "result", Objects.requireNonNull(result, "interaction result")));
        HttpResponse<InputStream> response = send("POST", "/v1/executions/" + path(context.invocationId())
                + "/interactions", body, "application/json");
        requireStatus(response, 202);
        close(response.body());
    }

    private byte[] request(String method, String path, byte[] body, String accept) {
        HttpResponse<InputStream> response = send(method, path, body, accept);
        requireStatus(response, 200);
        try (InputStream input = new BoundedInputStream(response.body(), maxPayloadBytes)) {
            return input.readAllBytes();
        } catch (IOException failure) {
            throw failure(ExternalProtocolError.TRANSPORT_FAILURE, "executor response failed", failure);
        }
    }

    private HttpResponse<InputStream> send(String method, String path, byte[] body, String accept) {
        if (body.length > maxPayloadBytes) throw failure(ExternalProtocolError.PAYLOAD_TOO_LARGE, "request too large");
        Instant now = clock.instant();
        String nonce = UUID.randomUUID().toString();
        String signature = sign(method, path, now, nonce, body);
        HttpRequest request = HttpRequest.newBuilder(origin.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("X-Dvara-Key-Id", keyId)
                .header("X-Dvara-Timestamp", now.toString())
                .header("X-Dvara-Nonce", nonce)
                .header("X-Dvara-Signature", signature)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw failure(ExternalProtocolError.TRANSPORT_FAILURE, "executor request interrupted", stopped);
        } catch (IOException failure) {
            throw failure(ExternalProtocolError.TRANSPORT_FAILURE, "executor request failed", failure);
        }
    }

    private String sign(String method, String path, Instant timestamp, String nonce, byte[] body) {
        char[] material = credentials.resolve();
        byte[] key = null;
        try {
            if (material == null || material.length == 0) throw failure(ExternalProtocolError.AUTHENTICATION_FAILED,
                    "executor signing credential unavailable");
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(material));
            key = new byte[bytes.remaining()];
            bytes.get(key);
            String canonical = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + hex(sha256(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (ExternalProtocolException safe) {
            throw safe;
        } catch (Exception failure) {
            throw failure(ExternalProtocolError.AUTHENTICATION_FAILED, "executor request signing failed", failure);
        } finally {
            if (material != null) Arrays.fill(material, '\0');
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static void validateNegotiation(ExternalProtocolRequirements required,
            ExternalExecutorDescriptor actual) {
        if (!required.protocolVersion().equals(actual.protocolVersion())
                || !required.schemaVersion().equals(actual.schemaVersion())
                || actual.assurance().ordinal() < required.assurance().ordinal()
                || actual.accessMode() != required.accessMode()
                || !actual.capabilities().containsAll(required.capabilities())) {
            throw failure(ExternalProtocolError.NEGOTIATION_MISMATCH,
                    "executor protocol, schema, assurance, access mode, or capabilities do not match");
        }
    }

    private static URI validateOrigin(URI value, boolean allowLoopbackHttp) {
        Objects.requireNonNull(value, "origin");
        if (value.getHost() == null || value.getUserInfo() != null || value.getQuery() != null
                || value.getFragment() != null || !(value.getPath().isEmpty() || value.getPath().equals("/"))) {
            throw new IllegalArgumentException("origin must contain only scheme, host, and optional port");
        }
        List<InetAddress> resolved = addresses(value.getHost());
        boolean https = value.getScheme().equalsIgnoreCase("https");
        boolean testLoopback = allowLoopbackHttp && value.getScheme().equalsIgnoreCase("http")
                && resolved.stream().allMatch(InetAddress::isLoopbackAddress);
        if (!https && !testLoopback) {
            throw new IllegalArgumentException("production executor origin must use HTTPS");
        }
        if (!testLoopback && resolved.stream().anyMatch(RemoteExecutorHttpClient::privateAddress)) {
            throw new IllegalArgumentException("executor origin must not resolve to a private address");
        }
        String normalized = value.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }

    private static List<InetAddress> addresses(String host) {
        try { return List.of(InetAddress.getAllByName(host)); }
        catch (IOException failure) { throw new IllegalArgumentException("executor host cannot be resolved", failure); }
    }

    private static boolean privateAddress(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress();
    }

    private static void requireStatus(HttpResponse<?> response, int expected) {
        if (response.statusCode() != expected) {
            close(response.body());
            throw failure(response.statusCode() == 401 || response.statusCode() == 403
                    ? ExternalProtocolError.AUTHENTICATION_FAILED : ExternalProtocolError.TRANSPORT_FAILURE,
                    "executor returned HTTP " + response.statusCode());
        }
    }

    private static byte[] encode(Object value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (JacksonException failure) { throw failure(ExternalProtocolError.MALFORMED_ENVELOPE,
                "cannot encode executor envelope", failure); }
    }

    private static <T> T decode(byte[] value, Class<T> type) {
        try { return JSON.readValue(value, type); }
        catch (JacksonException failure) { throw failure(ExternalProtocolError.MALFORMED_ENVELOPE,
                "cannot decode executor envelope", failure); }
    }

    private static <T> T decode(String value, Class<T> type) {
        return decode(value.getBytes(StandardCharsets.UTF_8), type);
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    private static String path(String value) { return required(value, "path").replaceAll("[^A-Za-z0-9._~-]", "_"); }
    private static String required(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    private static void close(Object body) { if (body instanceof InputStream input) try { input.close(); } catch (IOException ignored) { } }
    private static ExternalProtocolException failure(ExternalProtocolError error, String message) {
        return new ExternalProtocolException(error, message);
    }
    private static ExternalProtocolException failure(ExternalProtocolError error, String message, Throwable cause) {
        return new ExternalProtocolException(error, message, cause);
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private final int limit;
        private int count;
        private BoundedInputStream(InputStream input, int limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException { int value = super.read(); if (value >= 0) add(1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length); if (read > 0) add(read); return read;
        }
        private void add(int amount) { count += amount; if (count > limit) throw failure(
                ExternalProtocolError.PAYLOAD_TOO_LARGE, "executor response too large"); }
    }
}
