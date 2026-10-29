package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable scheme router for independently contributed credential providers. */
public final class CredentialProviderRegistry implements CredentialProvider {
    private final Map<String, CredentialProvider> providers;

    public CredentialProviderRegistry(List<CredentialProviderRegistration> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        Map<String, CredentialProvider> snapshot = new LinkedHashMap<>();
        for (CredentialProviderRegistration registration : registrations) {
            Objects.requireNonNull(registration, "credential provider registration");
            if (snapshot.putIfAbsent(registration.scheme(), registration.provider()) != null) {
                throw new IllegalArgumentException("duplicate credential provider scheme: " + registration.scheme());
            }
        }
        if (snapshot.isEmpty()) throw new IllegalArgumentException("at least one credential provider is required");
        providers = Map.copyOf(snapshot);
    }

    @Override
    public CredentialAuthorization authorize(CredentialRef credentialRef, InvocationIdentity identity, Instant now) {
        return authorize(CredentialUseRequest.withoutAgent(credentialRef, identity), now);
    }

    @Override
    public CredentialAuthorization authorize(CredentialUseRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        CredentialRef credentialRef = request.credentialRef();
        InvocationIdentity identity = request.identity();
        Objects.requireNonNull(credentialRef, "credentialRef");
        Objects.requireNonNull(identity, "identity");
        CredentialAuthorization authorization = provider(credentialRef).authorize(request, now);
        if (authorization == null || !credentialRef.equals(authorization.credentialRef())
                || !identity.equals(authorization.identity())) {
            throw new CredentialResolutionException("credential provider returned an invalid authorization");
        }
        return authorization;
    }

    @Override
    public ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
        Objects.requireNonNull(authorization, "authorization");
        ResolvedCredential credential = provider(authorization.credentialRef()).resolve(authorization, now);
        if (credential == null) {
            throw new CredentialResolutionException("credential provider returned no credential material");
        }
        return credential;
    }

    private CredentialProvider provider(CredentialRef reference) {
        String scheme;
        try {
            scheme = new URI(reference.id()).getScheme();
        } catch (URISyntaxException invalid) {
            throw unavailable();
        }
        CredentialProvider provider = scheme == null ? null : providers.get(scheme);
        if (provider == null) throw unavailable();
        return provider;
    }

    private static CredentialResolutionException unavailable() {
        return new CredentialResolutionException("no credential provider is registered for the reference scheme");
    }

    @Override
    public String toString() {
        return "CredentialProviderRegistry[schemes=" + providers.keySet().stream().sorted().toList() + "]";
    }
}
