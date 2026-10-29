package com.dvarahq.agentworks.credential;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** A credential provider bound to one lower-case {@code CredentialRef} URI scheme. */
public record CredentialProviderRegistration(String scheme, CredentialProvider provider) {
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]{1,31}$");

    public CredentialProviderRegistration {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(provider, "provider");
        if (!scheme.equals(scheme.toLowerCase(Locale.ROOT)) || !SCHEME.matcher(scheme).matches()) {
            throw new IllegalArgumentException("credential provider scheme must be a lower-case URI scheme");
        }
    }

    @Override
    public String toString() {
        return "CredentialProviderRegistration[scheme=" + scheme + ", provider="
                + provider.getClass().getName() + "]";
    }
}
