package com.dvarahq.agentworks.gateway;

/**
 * Stable, machine-readable gateway failure codes. A code marked "before provider contact" is only ever raised by Core
 * before any credential use or provider request. Whether a failed invocation actually reached the provider is never
 * inferred from the code: failure evidence ({@code providerContacted}) and any retry decision (#85) use the recorded
 * send state.
 */
public enum GatewayErrorCode {
    /** Release or invocation names a gateway that is not configured. Before provider contact. */
    GATEWAY_NOT_CONFIGURED(true),
    /** The configured gateway's adapter type or protocol differs from the pinned identity. Before provider contact. */
    GATEWAY_IDENTITY_CHANGED(true),
    /**
     * The gateway's adapter type is not permitted in this installation, for example the deterministic fake outside
     * local development. Release time and before provider contact.
     */
    GATEWAY_NOT_PERMITTED(true),
    /** The gateway's alias catalog does not contain the requested alias. Release time only. */
    MODEL_ALIAS_UNKNOWN(true),
    /** The release does not declare the requested model role. Before provider contact. */
    MODEL_ROLE_NOT_DECLARED(true),
    /** The release predates model resolution (manifest version 1) and must be re-released. Before provider contact. */
    MODEL_ROLE_UNRESOLVED(true),
    /** The release manifest version or shape is not one this reader implements. Before provider contact. */
    MANIFEST_UNSUPPORTED(true),
    /** A capability the request needs is unsupported and the captured policy requires it. Before provider contact. */
    CAPABILITY_UNSUPPORTED(true),
    /** The invocation identity's workspace differs from the pinned release's workspace. Before provider contact. */
    TENANT_MISMATCH(true),
    /** The request is structurally invalid. Before provider contact. */
    INVALID_REQUEST(true),
    /**
     * The gateway's credential could not be authorized or resolved, or expired. Not marked "before provider contact":
     * an adapter may use the credential after sending, so only the recorded send state says whether the turn started.
     */
    CREDENTIAL_UNAVAILABLE(false),
    PROVIDER_AUTHENTICATION_FAILED(false),
    PROVIDER_RATE_LIMITED(false),
    PROVIDER_REJECTED_REQUEST(false),
    PROVIDER_UNAVAILABLE(false),
    /** Core bulkhead has no capacity within the release-captured wait bound. */
    MODEL_CAPACITY_EXHAUSTED(true),
    /** Core circuit breaker is open for this tenant/release role/provider route. */
    MODEL_ROUTE_OPEN(true),
    /** A stream failed after at least one content chunk was delivered. */
    PARTIAL_STREAM(false),
    /** The provider answered with a malformed, truncated, or oversized response. */
    INVALID_PROVIDER_RESPONSE(false),
    /** The adapter failed unexpectedly; the original exception is not propagated because it may carry secrets. */
    ADAPTER_FAILURE(false),
    /** The invocation deadline elapsed. */
    TIMEOUT(false),
    /** The caller cancelled the invocation. */
    CANCELLED(false);

    private final boolean beforeProviderContact;

    GatewayErrorCode(boolean beforeProviderContact) {
        this.beforeProviderContact = beforeProviderContact;
    }

    /**
     * True when only Core raises this failure, always before any credential use or provider request. Evidence and retry
     * decisions do not use this flag; they use the recorded send state.
     */
    public boolean beforeProviderContact() {
        return beforeProviderContact;
    }
}
