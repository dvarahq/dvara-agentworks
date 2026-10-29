package com.dvarahq.agentworks.identity.web;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;
import java.util.Objects;

/** An unauthenticated bearer token or session cookie value awaiting a provider. It never prints its value. */
public final class CredentialAuthenticationToken extends AbstractAuthenticationToken {
    public enum Kind { BEARER, SESSION }

    private final Kind kind;
    private String value;

    private CredentialAuthenticationToken(Kind kind, String value) {
        super(List.of());
        this.kind = Objects.requireNonNull(kind, "kind");
        this.value = value == null ? "" : value;
        setAuthenticated(false);
    }

    public static CredentialAuthenticationToken bearer(String value) {
        return new CredentialAuthenticationToken(Kind.BEARER, value);
    }

    public static CredentialAuthenticationToken session(String value) {
        return new CredentialAuthenticationToken(Kind.SESSION, value);
    }

    public Kind kind() {
        return kind;
    }

    public String value() {
        return value;
    }

    @Override
    public Object getCredentials() {
        return value;
    }

    @Override
    public Object getPrincipal() {
        return kind;
    }

    @Override
    public void eraseCredentials() {
        value = "";
        super.eraseCredentials();
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "CredentialAuthenticationToken[kind=" + kind + "]";
    }
}
