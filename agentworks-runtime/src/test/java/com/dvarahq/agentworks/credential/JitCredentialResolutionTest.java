package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.agent.compiled.CompiledAgentDefinition;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JitCredentialResolutionTest {

    private static final Instant NOW = Instant.parse("2026-08-23T08:00:00Z");
    private static final CredentialRef REFERENCE = new CredentialRef("delegated-calendar-access");
    private static final String CREDENTIAL_A = "test-credential-A-7f2c";
    private static final String CREDENTIAL_B = "test-credential-B-9a4e";

    private RotatingFakeCredentialProvider provider;
    private List<CredentialAuditEvent> auditEvents;
    private JitCredentialToolInvoker invoker;

    @BeforeEach
    void setUp() {
        provider = new RotatingFakeCredentialProvider();
        auditEvents = new ArrayList<>();
        invoker = new JitCredentialToolInvoker(provider, auditEvents::add,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void compiledDefinitionContainsOnlyReferenceAndNonSecretMetadata() {
        CompiledAgentDefinition compiled = new CompiledAgentDefinition("credential-tool", List.of(REFERENCE));

        assertThat(compiled.credentialRefs()).containsExactly(REFERENCE);
        String serializedSnapshot = "{executorType=" + compiled.executorType()
                + ",credentialRefs=" + compiled.credentialRefs() + "}";
        assertThat(serializedSnapshot).contains(REFERENCE.id())
                .doesNotContain(CREDENTIAL_A, CREDENTIAL_B);
        assertThat(Arrays.stream(CompiledAgentDefinition.class.getRecordComponents())
                .filter(component -> component.getName().equals("credentialRefs"))
                .map(component -> component.getGenericType().getTypeName()))
                .singleElement().asString().contains(CredentialRef.class.getName());
    }

    @Test
    void authorizedInvocationResolvesCurrentCredentialImmediatelyBeforeUseAndZeroizesIt() {
        InvocationIdentity identity = identity();
        provider.put(REFERENCE, identity, CREDENTIAL_A, NOW.plusSeconds(60));
        AtomicReference<char[]> observedMaterial = new AtomicReference<>();

        String result = invoker.invoke(REFERENCE, identity, material -> {
            observedMaterial.set(material);
            assertThat(new String(material)).isEqualTo(CREDENTIAL_A);
            return "calendar-call-ok";
        });

        assertThat(result).isEqualTo("calendar-call-ok");
        assertThat(provider.timeline()).containsExactly("AUTHORIZE", "RESOLVE");
        assertThat(observedMaterial.get()).containsOnly('\0');
    }

    @Test
    void rotationChangesNextInvocationWithoutChangingCompiledRelease() {
        InvocationIdentity identity = identity();
        CompiledAgentDefinition compiled = new CompiledAgentDefinition("credential-tool", List.of(REFERENCE));
        int compiledIdentity = compiled.hashCode();
        List<String> usedVersions = new ArrayList<>();
        provider.put(REFERENCE, identity, CREDENTIAL_A, NOW.plusSeconds(60));

        invoker.invoke(REFERENCE, identity, material -> {
            usedVersions.add(new String(material));
            return "ok";
        });
        provider.put(REFERENCE, identity, CREDENTIAL_B, NOW.plusSeconds(120));
        invoker.invoke(REFERENCE, identity, material -> {
            usedVersions.add(new String(material));
            return "ok";
        });

        assertThat(usedVersions).containsExactly(CREDENTIAL_A, CREDENTIAL_B);
        assertThat(compiled.hashCode()).isEqualTo(compiledIdentity);
        assertThat(compiled.credentialRefs()).containsExactly(REFERENCE);
    }

    @Test
    void sameDeploymentResolvesEachUsersOwnDelegatedCredential() {
        InvocationIdentity userA = identity();
        InvocationIdentity userB = new InvocationIdentity(userA.organizationId(), userA.workspaceId(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        provider.put(REFERENCE, userA, CREDENTIAL_A, NOW.plusSeconds(60));
        provider.put(REFERENCE, userB, CREDENTIAL_B, NOW.plusSeconds(60));

        List<String> observed = new ArrayList<>();
        invoker.invoke(REFERENCE, userA, value -> { observed.add(new String(value)); return "ok"; });
        invoker.invoke(REFERENCE, userB, value -> { observed.add(new String(value)); return "ok"; });

        assertThat(observed).containsExactly(CREDENTIAL_A, CREDENTIAL_B);
    }

    @Test
    void crossUserSessionAndTenantScopesFailBeforeResolution() {
        InvocationIdentity owner = identity();
        provider.put(REFERENCE, owner, CREDENTIAL_A, NOW.plusSeconds(60));
        List<InvocationIdentity> mismatches = List.of(
                new InvocationIdentity(owner.organizationId(), owner.workspaceId(), UUID.randomUUID(),
                        owner.sessionId(), owner.delegatedSubjectId()),
                new InvocationIdentity(owner.organizationId(), owner.workspaceId(), owner.userId(),
                        UUID.randomUUID(), owner.delegatedSubjectId()),
                new InvocationIdentity(UUID.randomUUID(), owner.workspaceId(), owner.userId(),
                        owner.sessionId(), owner.delegatedSubjectId()),
                new InvocationIdentity(owner.organizationId(), UUID.randomUUID(), owner.userId(),
                        owner.sessionId(), owner.delegatedSubjectId()),
                new InvocationIdentity(owner.organizationId(), owner.workspaceId(), owner.userId(),
                        owner.sessionId(), UUID.randomUUID()));

        for (InvocationIdentity mismatch : mismatches) {
            assertThatThrownBy(() -> invoker.invoke(REFERENCE, mismatch, value -> "must-not-run"))
                    .isInstanceOf(CredentialResolutionException.class)
                    .hasMessageContaining("not authorized");
        }
        assertThat(provider.resolveCalls()).isZero();
        assertThat(auditEvents).hasSize(5).allMatch(event -> event.outcome().equals("DENIED_OR_FAILED"));
    }

    @Test
    void revokedAndExpiredDelegationFailClosedWithoutStaleReuse() {
        InvocationIdentity identity = identity();
        provider.put(REFERENCE, identity, CREDENTIAL_A, NOW.plusSeconds(60));
        assertThat(invoker.invoke(REFERENCE, identity, value -> "first-ok")).isEqualTo("first-ok");

        provider.revoke(REFERENCE, identity);
        assertThatThrownBy(() -> invoker.invoke(REFERENCE, identity, value -> "must-not-run"))
                .isInstanceOf(CredentialResolutionException.class)
                .hasMessageContaining("revoked");

        provider.put(REFERENCE, identity, CREDENTIAL_B, NOW.minusSeconds(1));
        assertThatThrownBy(() -> invoker.invoke(REFERENCE, identity, value -> "must-not-run"))
                .isInstanceOf(CredentialResolutionException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void providerOutageFailsSafelyWithoutCachedFallback() {
        InvocationIdentity identity = identity();
        provider.put(REFERENCE, identity, CREDENTIAL_A, NOW.plusSeconds(60));
        assertThat(invoker.invoke(REFERENCE, identity, value -> "first-ok")).isEqualTo("first-ok");
        provider.available(false);

        assertThatThrownBy(() -> invoker.invoke(REFERENCE, identity, value -> "must-not-run"))
                .isInstanceOf(CredentialResolutionException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void safeAuditPromptEventAndResolvedCredentialRenderingNeverExposeMaterial() {
        InvocationIdentity identity = identity();
        provider.put(REFERENCE, identity, CREDENTIAL_A, NOW.plusSeconds(60));
        AtomicReference<String> resolvedRendering = new AtomicReference<>();
        provider.onResolved(value -> resolvedRendering.set(value.toString()));

        String normalizedResult = invoker.invoke(REFERENCE, identity, value -> "tool-succeeded");
        String prompt = "Use credential reference " + REFERENCE.id();
        String immutableEventSnapshot = auditEvents.toString();
        String combinedSafeSurface = prompt + normalizedResult + immutableEventSnapshot + resolvedRendering.get()
                + provider.timeline();

        assertThat(combinedSafeSurface)
                .doesNotContain(CREDENTIAL_A, CREDENTIAL_B)
                .contains("<redacted>", REFERENCE.id(), "SUCCEEDED");
    }

    @Test
    void providerIssuedGrantIsSingleUseAndCannotBeReplayedAcrossResolution() {
        InvocationIdentity identity = identity();
        provider.put(REFERENCE, identity, CREDENTIAL_A, NOW.plusSeconds(60));
        CredentialAuthorization grant = provider.authorize(REFERENCE, identity, NOW);

        try (ResolvedCredential ignored = provider.resolve(grant, NOW)) {
            assertThat(ignored.expiresAt()).isAfter(NOW);
        }
        assertThatThrownBy(() -> provider.resolve(grant, NOW))
                .isInstanceOf(CredentialResolutionException.class)
                .hasMessageContaining("invalid or already used");
    }

    private static InvocationIdentity identity() {
        return new InvocationIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
    }

    private static final class RotatingFakeCredentialProvider implements CredentialProvider {
        private final Map<ScopeKey, Entry> entries = new HashMap<>();
        private final Map<UUID, ScopeKey> grants = new HashMap<>();
        private final List<String> timeline = new ArrayList<>();
        private boolean available = true;
        private int resolveCalls;
        private java.util.function.Consumer<ResolvedCredential> onResolved = ignored -> { };

        void put(CredentialRef reference, InvocationIdentity identity, String material, Instant expiresAt) {
            entries.put(new ScopeKey(reference, identity), new Entry(material.toCharArray(), expiresAt, false));
        }

        void revoke(CredentialRef reference, InvocationIdentity identity) {
            Entry current = entries.get(new ScopeKey(reference, identity));
            entries.put(new ScopeKey(reference, identity), new Entry(current.material(), current.expiresAt(), true));
        }

        void available(boolean value) {
            available = value;
        }

        void onResolved(java.util.function.Consumer<ResolvedCredential> callback) {
            onResolved = callback;
        }

        List<String> timeline() {
            return List.copyOf(timeline);
        }

        int resolveCalls() {
            return resolveCalls;
        }

        @Override
        public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
            timeline.add("AUTHORIZE");
            ensureAvailable();
            ScopeKey key = new ScopeKey(reference, identity);
            Entry entry = entries.get(key);
            if (entry == null) {
                throw new CredentialResolutionException("identity is not authorized for credential reference");
            }
            if (entry.revoked()) {
                throw new CredentialResolutionException("delegated credential is revoked");
            }
            if (!now.isBefore(entry.expiresAt())) {
                throw new CredentialResolutionException("delegated credential is expired");
            }
            UUID grantId = UUID.randomUUID();
            grants.put(grantId, key);
            return new CredentialAuthorization(grantId, reference, identity);
        }

        @Override
        public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
            timeline.add("RESOLVE");
            resolveCalls++;
            ensureAvailable();
            ScopeKey key = grants.remove(authorization.grantId());
            if (key == null || !key.equals(new ScopeKey(authorization.credentialRef(), authorization.identity()))) {
                throw new CredentialResolutionException("authorization grant is invalid or already used");
            }
            Entry entry = entries.get(key);
            if (entry == null || entry.revoked() || !now.isBefore(entry.expiresAt())) {
                throw new CredentialResolutionException("credential became unavailable, revoked, or expired");
            }
            ResolvedCredential credential = new ResolvedCredential(entry.material(), entry.expiresAt());
            onResolved.accept(credential);
            return credential;
        }

        private void ensureAvailable() {
            if (!available) {
                throw new CredentialResolutionException("credential provider is unavailable");
            }
        }

        private record ScopeKey(CredentialRef reference, InvocationIdentity identity) {
        }

        private record Entry(char[] material, Instant expiresAt, boolean revoked) {
        }
    }
}
