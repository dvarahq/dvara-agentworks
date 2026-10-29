package com.dvarahq.agentworks.observability;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Produces a bounded JSON support bundle with aggregate state and optional content-free correlation evidence. */
public final class SupportBundleService {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @FunctionalInterface
    interface SnapshotSource {
        SupportBundleRepository.Snapshot snapshot(WorkspaceId workspace, String traceId, int maxEvents);
    }

    private final SnapshotSource snapshots;
    private final AgentWorksObservabilityProperties.SupportBundle limits;
    private final Clock clock;

    public SupportBundleService(SupportBundleRepository repository,
            AgentWorksObservabilityProperties.SupportBundle limits, Clock clock) {
        this(Objects.requireNonNull(repository, "repository")::snapshot, limits, clock);
    }

    SupportBundleService(SnapshotSource snapshots, AgentWorksObservabilityProperties.SupportBundle limits, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public byte[] create(WorkspaceId workspace, String traceId) {
        String normalized = normalizeTrace(traceId);
        var snapshot = snapshots.snapshot(workspace, normalized, limits.getMaxEvents());
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("schemaVersion", 1);
        bundle.put("generatedAt", Instant.now(clock).toString());
        bundle.put("workspaceDigest", digest(workspace.value().toString()));
        bundle.put("redaction", "payloads, prompts, responses, actors, addresses, credentials and configuration values excluded");
        bundle.put("snapshot", snapshot);
        byte[] bytes = JSON.writeValueAsBytes(bundle);
        if (bytes.length > limits.getMaxBytes()) {
            throw new SupportBundleTooLargeException(limits.getMaxBytes());
        }
        return bytes;
    }

    static String normalizeTrace(String traceId) {
        if (traceId == null || traceId.isBlank()) return null;
        String normalized = traceId.strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{32}")) {
            throw new InvalidTraceIdException();
        }
        return normalized;
    }

    private static String digest(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static final class SupportBundleTooLargeException extends RuntimeException {
        private final int maximum;
        public SupportBundleTooLargeException(int maximum) {
            super("support bundle exceeds the configured maximum");
            this.maximum = maximum;
        }
        public int maximum() { return maximum; }
    }

    public static final class InvalidTraceIdException extends IllegalArgumentException {
        InvalidTraceIdException() {
            super("traceId must be 32 hexadecimal characters");
        }
    }
}
