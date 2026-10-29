package com.dvarahq.agentworks.credential.conformance;

import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialResolutionException;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Network-free credential provider for tests. Entries are isolated by the complete invocation identity, grants are
 * single-use, and every read observes the latest rotation or revocation without a cache.
 */
public final class DeterministicFakeCredentialProvider
        implements CredentialProvider, CredentialProviderConformanceHarness, AutoCloseable {

    public enum Operation { AUTHORIZE, RESOLVE }

    private final Map<ScopeKey, Entry> entries = new HashMap<>();
    private final Map<UUID, ScopeKey> grants = new HashMap<>();
    private final List<Operation> timeline = new ArrayList<>();
    private boolean available = true;
    private long nextGrant = 1;
    private int resolveCalls;

    @Override
    public synchronized CredentialProvider provider() {
        return this;
    }

    @Override
    public synchronized void put(CredentialRef reference, InvocationIdentity identity, char[] material,
            Instant expiresAt) {
        ScopeKey key = new ScopeKey(Objects.requireNonNull(reference, "reference"),
                Objects.requireNonNull(identity, "identity"));
        Objects.requireNonNull(material, "material");
        Entry previous = entries.put(key,
                new Entry(material, Objects.requireNonNull(expiresAt, "expiresAt"), false));
        if (previous != null) previous.erase();
    }

    /** Convenience overload that does not retain the String beyond the copied test fixture. */
    public void put(CredentialRef reference, InvocationIdentity identity, String material, Instant expiresAt) {
        char[] value = Objects.requireNonNull(material, "material").toCharArray();
        try {
            put(reference, identity, value, expiresAt);
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    @Override
    public synchronized void revoke(CredentialRef reference, InvocationIdentity identity) {
        ScopeKey key = new ScopeKey(Objects.requireNonNull(reference, "reference"),
                Objects.requireNonNull(identity, "identity"));
        Entry entry = entries.get(key);
        if (entry == null) throw new CredentialResolutionException("credential reference is not configured");
        entries.put(key, new Entry(new char[0], entry.expiresAt(), true));
        entry.erase();
    }

    @Override
    public synchronized void available(boolean value) {
        available = value;
    }

    @Override
    public synchronized int resolveCalls() {
        return resolveCalls;
    }

    public synchronized List<Operation> timeline() {
        return List.copyOf(timeline);
    }

    @Override
    public synchronized CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity,
            Instant now) {
        timeline.add(Operation.AUTHORIZE);
        ensureAvailable();
        ScopeKey key = new ScopeKey(Objects.requireNonNull(reference, "reference"),
                Objects.requireNonNull(identity, "identity"));
        Entry entry = entries.get(key);
        ensureUsable(entry, Objects.requireNonNull(now, "now"));
        UUID grantId = new UUID(0, nextGrant++);
        grants.put(grantId, key);
        return new CredentialAuthorization(grantId, reference, identity);
    }

    @Override
    public synchronized ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
        timeline.add(Operation.RESOLVE);
        resolveCalls++;
        ensureAvailable();
        Objects.requireNonNull(authorization, "authorization");
        ScopeKey expected = new ScopeKey(authorization.credentialRef(), authorization.identity());
        ScopeKey granted = grants.remove(authorization.grantId());
        if (!expected.equals(granted)) {
            throw new CredentialResolutionException("authorization grant is invalid or already used");
        }
        Entry entry = entries.get(expected);
        ensureUsable(entry, Objects.requireNonNull(now, "now"));
        return new ResolvedCredential(entry.material, entry.expiresAt());
    }

    @Override
    public synchronized void close() {
        entries.values().forEach(Entry::erase);
        entries.clear();
        grants.clear();
    }

    @Override
    public String toString() {
        return "DeterministicFakeCredentialProvider[material=<redacted>]";
    }

    private void ensureAvailable() {
        if (!available) throw new CredentialResolutionException("credential provider is unavailable");
    }

    private static void ensureUsable(Entry entry, Instant now) {
        if (entry == null) throw new CredentialResolutionException("identity is not authorized for credential reference");
        if (entry.revoked()) throw new CredentialResolutionException("credential is revoked");
        if (!now.isBefore(entry.expiresAt())) throw new CredentialResolutionException("credential is expired");
    }

    private record ScopeKey(CredentialRef reference, InvocationIdentity identity) { }

    private record Entry(char[] material, Instant expiresAt, boolean revoked) {
        private Entry {
            material = Arrays.copyOf(material, material.length);
        }

        private void erase() {
            Arrays.fill(material, '\0');
        }
    }
}
