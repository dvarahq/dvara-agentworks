package com.dvarahq.agentworks.domain.identity;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * How a principal proved its identity. Core defines browser sessions and API tokens; optional external identity
 * providers add their own values without changing tenant or resource authority.
 */
public record AuthenticationMethod(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    public static final AuthenticationMethod SESSION = new AuthenticationMethod("session");
    public static final AuthenticationMethod API_TOKEN = new AuthenticationMethod("api-token");

    public AuthenticationMethod {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) throw new IllegalArgumentException("invalid authentication method");
    }

    @Override
    public String toString() {
        return value;
    }
}
