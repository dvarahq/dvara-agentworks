package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunCheckpoint;
import com.dvarahq.agentworks.domain.run.RunEvent;
import com.dvarahq.agentworks.domain.run.RunEventType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical JSON, payload bounds, digests, and schema resolution for run events and checkpoints.
 *
 * <p>Canonical form: object keys sorted, no insignificant whitespace, decimals in plain notation. PostgreSQL
 * stores payloads as JSONB, which re-formats them, so digests are always recomputed over the canonical form of the
 * parsed value, never over stored bytes. A payload is a JSON object of at most {@value #MAX_PAYLOAD_BYTES} canonical
 * UTF-8 bytes and {@value #MAX_DEPTH} levels, with unique keys and no NUL characters; the database enforces a
 * larger bound on its own JSONB text form.
 */
public final class RunEventCodec {
    public static final int MAX_PAYLOAD_BYTES = 131_072;
    public static final int MAX_DEPTH = 32;
    public static final String GENESIS = "genesis";
    private static final Pattern IDENTITY = Pattern.compile("[A-Za-z0-9:._@/+-]{1,128}");
    private static final Pattern TRACE = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN = Pattern.compile("[0-9a-f]{16}");

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final EventSchemas schemas;

    /** A stored event resolved to a known type and lifted to its current payload version. */
    public record DecodedEvent(RunEvent raw, RunEventType type, ObjectNode payload) {}

    public RunEventCodec(EventSchemas schemas) {
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    public static RunEventCodec standard() {
        return new RunEventCodec(EventSchemas.CURRENT);
    }

    public EventSchemas schemas() {
        return schemas;
    }

    public JsonMapper mapper() {
        return mapper;
    }

    /** Parses and validates a payload object and returns its canonical text. */
    public String canonicalPayload(String json) {
        return canonical(object(json));
    }

    /** Converts a value (map, record, or tree) into a validated canonical payload object. */
    public String canonicalPayload(Object value) {
        if (value instanceof String text) return canonicalPayload(text);
        JsonNode tree;
        try {
            tree = mapper.valueToTree(value);
        } catch (JacksonException invalid) {
            throw new RunEngineException("INVALID_PAYLOAD", "the payload is not JSON-serializable", invalid);
        }
        if (!(tree instanceof ObjectNode object)) throw new RunEngineException("INVALID_PAYLOAD", "a payload is a JSON object");
        validate(object, 1);
        // Round-trip through text so numbers take the same representation they have when read back.
        return canonicalPayload(canonical(object));
    }

    /** Parses a payload object, enforcing the bounds. */
    public ObjectNode object(String json) {
        if (json == null) throw new RunEngineException("INVALID_PAYLOAD", "a payload is required");
        JsonNode tree;
        try {
            tree = mapper.readTree(json);
        } catch (JacksonException invalid) {
            throw new RunEngineException("INVALID_PAYLOAD", "the payload is not valid JSON: " + invalid.getOriginalMessage(),
                    invalid);
        }
        if (!(tree instanceof ObjectNode object)) throw new RunEngineException("INVALID_PAYLOAD", "a payload is a JSON object");
        validate(object, 1);
        String canonical = canonical(object);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new RunEngineException("PAYLOAD_TOO_LARGE", "a payload is at most " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return object;
    }

    public String canonical(JsonNode node) {
        StringBuilder out = new StringBuilder();
        try {
            write(node, out);
        } catch (JacksonException | ArithmeticException | NumberFormatException invalid) {
            throw new RunEngineException("INVALID_PAYLOAD", "the payload cannot be canonicalized", invalid);
        }
        return out.toString();
    }

    public static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /**
     * The chained event digest: the previous event digest (or {@value #GENESIS}) with this event's header and
     * payload digest. Any edit, gap, or reordering changes every later digest.
     */
    public static String eventDigest(String previousDigest, RunEvent event) {
        return sha256(String.join("\n", "agentworks.run-event.v1", previousDigest == null ? GENESIS : previousDigest,
                event.workspaceId().toString(), event.runId().toString(), Long.toString(event.sequence()), event.type(),
                Integer.toString(event.schemaVersion()), event.payloadDigest(), nullable(event.traceId()),
                nullable(event.spanId()), event.releaseId().toString(), event.deploymentId().toString(),
                event.revisionId().toString(), event.cohortId().toString(), event.actor(), nullable(event.leaseOwner()),
                Long.toString(event.leaseFence()), Long.toString(micros(event.createdAt()))));
    }

    /** Validates an actor or lease-owner identity: 1 to 128 characters of a restricted set. */
    public static String identity(String value, String name) {
        if (value == null || !IDENTITY.matcher(value).matches()) {
            throw new RunEngineException("INVALID_EVENT", name + " must be 1 to 128 characters of [A-Za-z0-9:._@/+-]");
        }
        return value;
    }

    public static String traceId(String value) {
        if (value == null || !TRACE.matcher(value).matches() || value.chars().allMatch(c -> c == '0')) {
            throw new RunEngineException("INVALID_EVENT", "a trace id is 32 lowercase hex characters and not all zero");
        }
        return value;
    }

    public static String spanId(String value) {
        if (value != null && (!SPAN.matcher(value).matches() || value.chars().allMatch(c -> c == '0'))) {
            throw new RunEngineException("INVALID_EVENT", "a span id is 16 lowercase hex characters and not all zero");
        }
        return value;
    }

    public static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    /**
     * Resolves a stored event: known type, supported version (upcast if older), valid payload whose canonical
     * digest matches the stored digest.
     */
    public DecodedEvent decode(RunEvent event) {
        RunEventType type = RunEventType.of(event.type()).orElseThrow(() -> new RunEngineException("UNKNOWN_EVENT_TYPE",
                "event " + event.sequence() + " has unknown type " + event.type()));
        ObjectNode payload = object(event.payloadJson());
        if (!sha256(canonical(payload)).equals(event.payloadDigest())) {
            throw new RunEngineException("CORRUPT_HISTORY", "event " + event.sequence() + " payload does not match its digest");
        }
        return new DecodedEvent(event, type, schemas.upcast(type, event.schemaVersion(), payload));
    }

    /**
     * Decodes a full history and verifies its integrity: gapless sequence from 1, the digest chain, and the
     * payload digests. Failures are typed ({@code CORRUPT_HISTORY}, {@code UNKNOWN_EVENT_TYPE},
     * {@code UNSUPPORTED_EVENT_VERSION}); nothing is repaired or invented.
     */
    public List<DecodedEvent> decodeHistory(List<RunEvent> events) {
        return decodeRange(null, 1, events);
    }

    /**
     * Decodes a contiguous slice of a history and verifies it as {@link #decodeHistory} does: the slice must start at
     * {@code firstSequence} and continue gaplessly, and its first event must chain from {@code previousDigest}, the
     * event digest of the event at {@code firstSequence - 1} ({@code null} when the slice starts at 1). A page of a
     * history is therefore anchored to the event before it, and consecutive pages form one verified chain.
     */
    public List<DecodedEvent> decodeRange(String previousDigest, long firstSequence, List<RunEvent> events) {
        if (firstSequence < 1) throw new IllegalArgumentException("sequences start at 1");
        if ((firstSequence == 1) != (previousDigest == null)) {
            throw new IllegalArgumentException("only a slice starting at sequence 1 has no previous digest");
        }
        List<DecodedEvent> decoded = new ArrayList<>(events.size());
        String previous = previousDigest;
        long expected = firstSequence;
        for (RunEvent event : events) {
            if (event.sequence() != expected) {
                throw new RunEngineException("CORRUPT_HISTORY", "expected event " + expected + " but found " + event.sequence());
            }
            if (!eventDigest(previous, event).equals(event.eventDigest())) {
                throw new RunEngineException("CORRUPT_HISTORY", "event " + event.sequence() + " breaks the digest chain");
            }
            decoded.add(decode(event));
            previous = event.eventDigest();
            expected++;
        }
        return decoded;
    }

    public void verifyCheckpoint(RunCheckpoint checkpoint) {
        if (!sha256(canonicalPayload(checkpoint.payloadJson())).equals(checkpoint.payloadDigest())) {
            throw new RunEngineException("CORRUPT_HISTORY", "checkpoint " + checkpoint.sequence() + " does not match its digest");
        }
    }

    private void validate(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) throw new RunEngineException("INVALID_PAYLOAD", "a payload nests at most " + MAX_DEPTH + " levels");
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                noNul(property.getKey());
                validate(property.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node.values()) validate(element, depth + 1);
        } else if (node.isString()) {
            noNul(node.stringValue());
        } else if (node.isNumber() && !node.isIntegralNumber() && !node.isBigDecimal()
                && !Double.isFinite(node.doubleValue())) {
            throw new RunEngineException("INVALID_PAYLOAD", "payload numbers are finite");
        }
    }

    /** Rejects NUL and unpaired surrogates, which PostgreSQL would reject or silently replace. */
    private static void noNul(String value) {
        if (value.indexOf('\u0000') >= 0) throw new RunEngineException("INVALID_PAYLOAD", "payload text cannot contain NUL");
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (Character.isHighSurrogate(c) && index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(c)) {
                throw new RunEngineException("INVALID_PAYLOAD", "payload text cannot contain unpaired surrogates");
            }
        }
    }

    private void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            names.sort(null);
            out.append('{');
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) out.append(',');
                out.append(mapper.writeValueAsString(names.get(index))).append(':');
                write(node.get(names.get(index)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            int index = 0;
            for (JsonNode element : node.values()) {
                if (index++ > 0) out.append(',');
                write(element, out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            out.append(node.isIntegralNumber() ? node.bigIntegerValue().toString() : node.decimalValue().toPlainString());
        } else if (node.isString()) {
            out.append(mapper.writeValueAsString(node.stringValue()));
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNull()) {
            out.append("null");
        } else {
            throw new RunEngineException("INVALID_PAYLOAD", "unsupported JSON value");
        }
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }
}
