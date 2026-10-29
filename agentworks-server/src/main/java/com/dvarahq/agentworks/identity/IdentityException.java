package com.dvarahq.agentworks.identity;

import java.util.Objects;

/**
 * A typed identity or access failure. Messages are fixed text: they never echo a username, password, token, or
 * any other request content.
 */
public final class IdentityException extends RuntimeException {
    /** Every sign-in, token, and session failure; deliberately indistinguishable across causes. */
    public static final String AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    /** The bootstrap token is unknown, expired, consumed, or users already exist. */
    public static final String BOOTSTRAP_UNAVAILABLE = "BOOTSTRAP_UNAVAILABLE";
    public static final String PASSWORD_POLICY = "PASSWORD_POLICY";
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String USERNAME_TAKEN = "USERNAME_TAKEN";
    public static final String LAST_ADMINISTRATOR = "LAST_ADMINISTRATOR";
    public static final String ROLE_NOT_GRANTABLE = "ROLE_NOT_GRANTABLE";
    public static final String WORKSPACE_SCOPE_UNAVAILABLE = "WORKSPACE_SCOPE_UNAVAILABLE";
    public static final String TOO_MANY_ATTEMPTS = "TOO_MANY_ATTEMPTS";

    private final String code;

    public IdentityException(String code, String message) {
        super(message, null, false, false);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String code() {
        return code;
    }

    public static IdentityException authenticationFailed() {
        return new IdentityException(AUTHENTICATION_FAILED, "The credentials are not valid.");
    }

    public static IdentityException notFound() {
        return new IdentityException(NOT_FOUND, "The resource was not found.");
    }
}
