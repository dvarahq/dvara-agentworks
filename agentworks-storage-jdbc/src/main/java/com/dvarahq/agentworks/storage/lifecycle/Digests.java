package com.dvarahq.agentworks.storage.lifecycle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Non-reversible SHA-256 digests for idempotency keys, fingerprints, and canonical content. */
public final class Digests {
    private Digests() {}

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String prefixed(String value) {
        return "sha256:" + sha256Hex(value);
    }
}
