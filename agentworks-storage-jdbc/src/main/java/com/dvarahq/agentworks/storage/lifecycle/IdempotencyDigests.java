package com.dvarahq.agentworks.storage.lifecycle;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Versioned, purpose-separated HMAC-SHA-256 digests for idempotency keys and request fingerprints, scoped by
 * workspace and namespace. Deterministic lookup needs a keyed digest rather than a random salt; the key version is
 * stored with each record. Every key version must be retained until the records digested with it are purged after
 * namespace retirement (#59), or retries under that key could no longer be recognized.
 *
 * <p>Version {@value #LEGACY_VERSION} recognizes the unkeyed SHA-256 digests written before issue #155.
 */
public final class IdempotencyDigests {
    public static final int LEGACY_VERSION = 0;
    private static final int MINIMUM_KEY_BYTES = 32;

    public record Digest(int version, String hex) {}

    private final Map<Integer, SecretKeySpec> keys;
    private final int activeVersion;

    public IdempotencyDigests(Map<Integer, byte[]> keys, int activeVersion) {
        Objects.requireNonNull(keys, "keys");
        Map<Integer, SecretKeySpec> retained = new TreeMap<>();
        keys.forEach((version, material) -> {
            if (version == null || version <= LEGACY_VERSION) throw new IllegalArgumentException("key versions start at 1");
            if (material == null || material.length < MINIMUM_KEY_BYTES) {
                throw new IllegalArgumentException("idempotency HMAC keys need at least 32 bytes");
            }
            retained.put(version, new SecretKeySpec(material.clone(), "HmacSHA256"));
        });
        if (!retained.containsKey(activeVersion)) throw new IllegalArgumentException("the active key version is not configured");
        this.keys = Map.copyOf(retained);
        this.activeVersion = activeVersion;
    }

    public int activeVersion() {
        return activeVersion;
    }

    /** Versions whose digests can still be recomputed: every configured key plus the legacy unkeyed version. */
    public Set<Integer> retainedVersions() {
        Set<Integer> versions = new TreeSet<>(keys.keySet());
        versions.add(LEGACY_VERSION);
        return Collections.unmodifiableSet(versions);
    }

    public Digest key(String workspace, String namespace, String key) {
        return new Digest(activeVersion, keyed(activeVersion, "idempotency-key", workspace, namespace, key));
    }

    /** Recomputes a key under a retained version, for durable records pinned before key rotation. */
    public Optional<Digest> key(int version, String workspace, String namespace, String key) {
        if (!keys.containsKey(version)) return Optional.empty();
        return Optional.of(new Digest(version, keyed(version, "idempotency-key", workspace, namespace, key)));
    }

    /**
     * The per-key lock identity. It must not depend on the key version: during a rolling rotation, nodes with
     * different active versions must still serialize the same key, or both could miss and execute it.
     */
    public String lockScope(String namespace, String key) {
        return Digests.sha256Hex(namespace + "\n" + key);
    }

    /** Every digest under which this key may already be recorded: each retained version plus the legacy digest. */
    public List<Digest> keyCandidates(String workspace, String namespace, String key) {
        List<Digest> candidates = new ArrayList<>();
        keys.keySet().forEach(version -> candidates.add(new Digest(version,
                keyed(version, "idempotency-key", workspace, namespace, key))));
        candidates.add(new Digest(LEGACY_VERSION, Digests.sha256Hex(namespace + "\n" + key)));
        return candidates;
    }

    /** The request fingerprint under {@code version}; empty when that key version is no longer retained. */
    public Optional<String> fingerprint(int version, String workspace, String namespace, String canonicalRequest) {
        if (version == LEGACY_VERSION) return Optional.of(Digests.sha256Hex(canonicalRequest));
        if (!keys.containsKey(version)) return Optional.empty();
        return Optional.of(keyed(version, "request-fingerprint", workspace, namespace, canonicalRequest));
    }

    private String keyed(int version, String purpose, String workspace, String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(keys.get(version));
            for (String part : List.of(purpose, workspace, namespace, value)) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                mac.update((byte) (bytes.length >>> 24));
                mac.update((byte) (bytes.length >>> 16));
                mac.update((byte) (bytes.length >>> 8));
                mac.update((byte) bytes.length);
                mac.update(bytes);
            }
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "IdempotencyDigests[activeVersion=" + activeVersion + ", versions=" + keys.keySet() + "]";
    }
}
