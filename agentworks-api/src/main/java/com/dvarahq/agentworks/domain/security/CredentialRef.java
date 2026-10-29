package com.dvarahq.agentworks.domain.security;

import java.util.Objects;

/** Opaque reference to credential material resolved only at an authorized JIT boundary. */
public record CredentialRef(String id) {

    public CredentialRef {
        Objects.requireNonNull(id, "id");
    }
}
