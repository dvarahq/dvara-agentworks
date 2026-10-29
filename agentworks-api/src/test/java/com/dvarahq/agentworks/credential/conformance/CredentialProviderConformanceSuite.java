package com.dvarahq.agentworks.credential.conformance;

import com.dvarahq.agentworks.credential.CredentialAuthorization;
import com.dvarahq.agentworks.credential.CredentialProvider;
import com.dvarahq.agentworks.credential.CredentialResolutionException;
import com.dvarahq.agentworks.credential.ResolvedCredential;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Credential-provider TCK. Provider modules extend this class and supply a fresh controllable harness for each test.
 * The suite proves JIT rotation, complete identity isolation, expiry, revocation, outage behavior, single-use grants,
 * zeroization, and secret-free diagnostics without prescribing a provider's storage technology.
 */
public abstract class CredentialProviderConformanceSuite {

    protected static final Instant NOW = Instant.parse("2026-08-23T08:00:00Z");
    protected static final CredentialRef REFERENCE = new CredentialRef("conformance:test-credential");
    protected static final String SECRET_A = "credential-tck-secret-a-7f2c";
    protected static final String SECRET_B = "credential-tck-secret-b-9a4e";

    private CredentialProviderConformanceHarness harness;
    private CredentialProvider provider;

    protected abstract CredentialProviderConformanceHarness createHarness();

    @BeforeEach
    void startProvider() {
        harness = createHarness();
        provider = harness.provider();
    }

    @AfterEach
    void stopProvider() throws Exception {
        if (harness instanceof AutoCloseable closeable) closeable.close();
    }

    @Test
    void resolvesCurrentMaterialAndZeroizesItAfterUse() {
        InvocationIdentity identity = identity();
        put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        AtomicReference<char[]> observed = new AtomicReference<>();

        CredentialAuthorization grant = provider.authorize(REFERENCE, identity, NOW);
        try (ResolvedCredential credential = provider.resolve(grant, NOW)) {
            credential.use(NOW, material -> {
                observed.set(material);
                assertThat(new String(material)).isEqualTo(SECRET_A);
                return null;
            });
        }

        assertThat(observed.get()).containsOnly('\0');
    }

    @Test
    void rotationChangesTheNextAuthorizationWithoutChangingTheReference() {
        InvocationIdentity identity = identity();
        put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));

        assertThat(resolve(REFERENCE, identity, NOW)).isEqualTo(SECRET_A);
        put(REFERENCE, identity, SECRET_B, NOW.plusSeconds(120));
        assertThat(resolve(REFERENCE, identity, NOW)).isEqualTo(SECRET_B);
    }

    @Test
    void everyIdentityDimensionIsolatedBeforeResolution() {
        InvocationIdentity owner = identity();
        put(REFERENCE, owner, SECRET_A, NOW.plusSeconds(60));
        List<InvocationIdentity> mismatches = List.of(
                new InvocationIdentity(UUID.randomUUID(), owner.workspaceId(), owner.userId(), owner.sessionId(),
                        owner.delegatedSubjectId()),
                new InvocationIdentity(owner.organizationId(), UUID.randomUUID(), owner.userId(), owner.sessionId(),
                        owner.delegatedSubjectId()),
                new InvocationIdentity(owner.organizationId(), owner.workspaceId(), UUID.randomUUID(), owner.sessionId(),
                        owner.delegatedSubjectId()),
                new InvocationIdentity(owner.organizationId(), owner.workspaceId(), owner.userId(), UUID.randomUUID(),
                        owner.delegatedSubjectId()),
                new InvocationIdentity(owner.organizationId(), owner.workspaceId(), owner.userId(), owner.sessionId(),
                        UUID.randomUUID()));

        mismatches.forEach(mismatch -> assertThatThrownBy(() -> provider.authorize(REFERENCE, mismatch, NOW))
                .isInstanceOf(CredentialResolutionException.class));

        assertThat(harness.resolveCalls()).isZero();
    }

    @Test
    void expiryRevocationAndOutageFailClosedWithoutFallback() {
        InvocationIdentity identity = identity();
        put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        assertThat(resolve(REFERENCE, identity, NOW)).isEqualTo(SECRET_A);

        harness.revoke(REFERENCE, identity);
        assertThatThrownBy(() -> provider.authorize(REFERENCE, identity, NOW))
                .isInstanceOf(CredentialResolutionException.class);

        put(REFERENCE, identity, SECRET_B, NOW);
        assertThatThrownBy(() -> provider.authorize(REFERENCE, identity, NOW))
                .isInstanceOf(CredentialResolutionException.class);

        put(REFERENCE, identity, SECRET_B, NOW.plusSeconds(60));
        harness.available(false);
        assertThatThrownBy(() -> provider.authorize(REFERENCE, identity, NOW))
                .isInstanceOf(CredentialResolutionException.class);
    }

    @Test
    void authorizationIsRecheckedAtResolutionAfterStateChanges() {
        InvocationIdentity identity = identity();
        put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        CredentialAuthorization revoked = provider.authorize(REFERENCE, identity, NOW);
        harness.revoke(REFERENCE, identity);
        assertThatThrownBy(() -> provider.resolve(revoked, NOW))
                .isInstanceOf(CredentialResolutionException.class);

        put(REFERENCE, identity, SECRET_B, NOW.plusSeconds(60));
        CredentialAuthorization expired = provider.authorize(REFERENCE, identity, NOW);
        assertThatThrownBy(() -> provider.resolve(expired, NOW.plusSeconds(60)))
                .isInstanceOf(CredentialResolutionException.class);

        CredentialAuthorization unavailable = provider.authorize(REFERENCE, identity, NOW);
        harness.available(false);
        assertThatThrownBy(() -> provider.resolve(unavailable, NOW))
                .isInstanceOf(CredentialResolutionException.class);
    }

    @Test
    void grantsAreSingleUseAndCannotBeReplayedWithChangedScope() {
        InvocationIdentity identity = identity();
        put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        CredentialAuthorization grant = provider.authorize(REFERENCE, identity, NOW);

        try (ResolvedCredential ignored = provider.resolve(grant, NOW)) {
            assertThat(ignored.expiresAt()).isAfter(NOW);
        }
        assertThatThrownBy(() -> provider.resolve(grant, NOW))
                .isInstanceOf(CredentialResolutionException.class);

        CredentialAuthorization fresh = provider.authorize(REFERENCE, identity, NOW);
        CredentialAuthorization changed = new CredentialAuthorization(fresh.grantId(), fresh.credentialRef(), identity());
        assertThatThrownBy(() -> provider.resolve(changed, NOW))
                .isInstanceOf(CredentialResolutionException.class);
    }

    @Test
    void providerAuthorizationAndDiagnosticsNeverExposeMaterial() {
        InvocationIdentity identity = identity();
        put(REFERENCE, identity, SECRET_A, NOW.plusSeconds(60));
        CredentialAuthorization authorization = provider.authorize(REFERENCE, identity, NOW);

        assertThat(provider.toString() + authorization).doesNotContain(SECRET_A, SECRET_B);
    }

    private String resolve(CredentialRef reference, InvocationIdentity identity, Instant now) {
        CredentialAuthorization grant = provider.authorize(reference, identity, now);
        try (ResolvedCredential credential = provider.resolve(grant, now)) {
            return credential.use(now, String::new);
        }
    }

    private void put(CredentialRef reference, InvocationIdentity identity, String value, Instant expiresAt) {
        char[] material = value.toCharArray();
        try {
            harness.put(reference, identity, material, expiresAt);
        } finally {
            Arrays.fill(material, '\0');
        }
    }

    protected static InvocationIdentity identity() {
        return new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
    }
}
