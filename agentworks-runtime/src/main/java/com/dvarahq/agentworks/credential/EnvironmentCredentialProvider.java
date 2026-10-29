package com.dvarahq.agentworks.credential;

import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JIT provider for secrets injected as process environment variables. Configuration contains only an exact reference,
 * variable name, and authority scope. Values are never cached and are read only while resolving a single-use grant.
 */
public final class EnvironmentCredentialProvider implements CredentialProvider {
    public static final String SCHEME = "env";
    public static final Duration DEFAULT_GRANT_TTL = Duration.ofSeconds(5);
    private static final Pattern VARIABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** Non-secret metadata for one exact reference. */
    public record Binding(String variable, CredentialScope scope) {
        public Binding {
            Objects.requireNonNull(variable, "variable");
            Objects.requireNonNull(scope, "scope");
            if (!VARIABLE.matcher(variable).matches()) {
                throw new IllegalArgumentException("environment variable name is invalid");
            }
        }

        @Override
        public String toString() {
            return "Binding[variable=<redacted>, scope=" + scope.level() + "]";
        }
    }

    /** Separate boundary so tests and embedding applications can supply a controlled environment source. */
    @FunctionalInterface
    public interface EnvironmentLookup {
        String read(String variable);
    }

    private final Map<CredentialRef, Binding> bindings;
    private final Duration grantTtl;
    private final EnvironmentLookup environment;
    private final Map<UUID, Grant> grants = new LinkedHashMap<>();

    public EnvironmentCredentialProvider(Map<CredentialRef, Binding> bindings) {
        this(bindings, DEFAULT_GRANT_TTL, System::getenv);
    }

    public EnvironmentCredentialProvider(Map<CredentialRef, Binding> bindings, Duration grantTtl,
            EnvironmentLookup environment) {
        Objects.requireNonNull(bindings, "bindings");
        Map<CredentialRef, Binding> snapshot = new LinkedHashMap<>();
        bindings.forEach((reference, binding) -> {
            requireEnvironmentReference(reference);
            snapshot.put(reference, Objects.requireNonNull(binding, "binding"));
        });
        if (snapshot.isEmpty()) throw new IllegalArgumentException("at least one environment credential is required");
        this.bindings = Map.copyOf(snapshot);
        this.grantTtl = Objects.requireNonNull(grantTtl, "grantTtl");
        if (grantTtl.isZero() || grantTtl.isNegative()) {
            throw new IllegalArgumentException("environment credential grant TTL must be positive");
        }
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    public CredentialProviderRegistration registration() {
        return new CredentialProviderRegistration(SCHEME, this);
    }

    @Override
    public CredentialAuthorization authorize(CredentialRef reference, InvocationIdentity identity, Instant now) {
        return authorize(CredentialUseRequest.withoutAgent(reference, identity), now);
    }

    @Override
    public synchronized CredentialAuthorization authorize(CredentialUseRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        Binding binding = bindings.get(request.credentialRef());
        if (binding == null || !binding.scope().permits(request)) {
            throw new CredentialResolutionException("credential reference is unavailable for this scope");
        }
        grants.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        UUID grantId = UUID.randomUUID();
        grants.put(grantId, new Grant(request, binding, now.plus(grantTtl)));
        return new CredentialAuthorization(grantId, request.credentialRef(), request.identity());
    }

    @Override
    public synchronized ResolvedCredential resolve(CredentialAuthorization authorization, Instant now) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(now, "now");
        Grant grant = grants.remove(authorization.grantId());
        if (grant == null || !grant.request().credentialRef().equals(authorization.credentialRef())
                || !grant.request().identity().equals(authorization.identity())) {
            throw new CredentialResolutionException("authorization grant is invalid or already used");
        }
        if (!now.isBefore(grant.expiresAt())) {
            throw new CredentialResolutionException("authorization grant is expired");
        }

        String value;
        try {
            value = environment.read(grant.binding().variable());
        } catch (RuntimeException unavailable) {
            throw new CredentialResolutionException("environment credential source is unavailable");
        }
        if (value == null || value.isEmpty()) {
            throw new CredentialResolutionException("environment credential is not available");
        }
        char[] material = value.toCharArray();
        try {
            return new ResolvedCredential(material, grant.expiresAt());
        } finally {
            Arrays.fill(material, '\0');
        }
    }

    @Override
    public String toString() {
        return "EnvironmentCredentialProvider[bindings=" + bindings.size() + ", material=<redacted>]";
    }

    private static void requireEnvironmentReference(CredentialRef reference) {
        Objects.requireNonNull(reference, "reference");
        try {
            URI uri = new URI(reference.id());
            if (!SCHEME.equals(uri.getScheme()) || uri.isOpaque() && uri.getSchemeSpecificPart().isBlank()
                    || !uri.isOpaque() && uri.getHost() == null) {
                throw new IllegalArgumentException("environment credential reference must use the env scheme");
            }
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("environment credential reference must use the env scheme");
        }
    }

    private record Grant(CredentialUseRequest request, Binding binding, Instant expiresAt) { }
}
