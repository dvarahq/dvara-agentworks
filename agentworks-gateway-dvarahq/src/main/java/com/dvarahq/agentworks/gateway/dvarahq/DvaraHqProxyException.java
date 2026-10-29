package com.dvarahq.agentworks.gateway.dvarahq;

/** Stable, secret-free MCP/A2A proxy failure. */
public final class DvaraHqProxyException extends RuntimeException {
    private final Code code;
    public DvaraHqProxyException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
    public enum Code {
        NOT_CONFIGURED, INVALID_REQUEST, TENANT_MISMATCH, CREDENTIAL_UNAVAILABLE,
        AUTHENTICATION_FAILED, RATE_LIMITED, UNAVAILABLE, TIMEOUT, CANCELLED,
        PROTOCOL_MISMATCH, INVALID_RESPONSE, RESPONSE_LIMIT
    }
}
