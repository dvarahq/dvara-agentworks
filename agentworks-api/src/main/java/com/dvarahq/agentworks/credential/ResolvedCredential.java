package com.dvarahq.agentworks.credential;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/** Ephemeral credential material that is zeroized when its JIT use ends. */
public final class ResolvedCredential implements AutoCloseable {

    private final char[] material;
    private final Instant expiresAt;
    private boolean closed;

    public ResolvedCredential(char[] material, Instant expiresAt) {
        this.material = Arrays.copyOf(Objects.requireNonNull(material, "material"), material.length);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public synchronized <T> T use(Instant now, Function<char[], T> operation) {
        if (closed) {
            throw new IllegalStateException("credential material is closed");
        }
        if (!now.isBefore(expiresAt)) {
            throw new CredentialResolutionException("credential material is expired");
        }
        return operation.apply(material);
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(material, '\0');
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "ResolvedCredential[material=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
