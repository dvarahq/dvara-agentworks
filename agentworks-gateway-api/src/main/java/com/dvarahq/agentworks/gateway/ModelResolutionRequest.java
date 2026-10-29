package com.dvarahq.agentworks.gateway;

import java.util.Objects;
import java.util.regex.Pattern;

/** Asks a gateway to resolve one role's logical alias to a concrete model identity. */
public record ModelResolutionRequest(ModelRole role, String alias) {

    /** Same pattern as the AgentSpec {@code models.<role>.model} field. */
    public static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/@-]*$");

    public ModelResolutionRequest {
        Objects.requireNonNull(role, "role");
        requireAlias(alias);
    }

    public static void requireAlias(String alias) {
        Objects.requireNonNull(alias, "alias");
        if (alias.length() > 256 || !ALIAS.matcher(alias).matches()) {
            throw new IllegalArgumentException("model alias must match " + ALIAS.pattern());
        }
    }
}
