package com.dvarahq.agentworks.gateway;

import java.util.Objects;

/**
 * Immutable release evidence supplied to an adapter for one invocation. Mutable gateway configuration is deliberately
 * absent: post-release alias edits must not change verification or observation semantics for a pinned run.
 *
 * @param scope tenant, release, run, trace, role, and pinned gateway/model identity
 * @param expectedFingerprint the release-pinned provider fingerprint, or {@code null}
 * @param fingerprintMatchRequired whether the release requires an exact served-fingerprint match
 */
public record AdapterInvocationEvidence(GatewayEvent.Scope scope, String expectedFingerprint,
        boolean fingerprintMatchRequired) {
    public AdapterInvocationEvidence {
        Objects.requireNonNull(scope, "scope");
        if (expectedFingerprint != null) {
            GatewayDescriptor.requireText(expectedFingerprint, "expectedFingerprint");
        }
        if (fingerprintMatchRequired && expectedFingerprint == null) {
            throw new IllegalArgumentException("a required fingerprint match needs release-pinned evidence");
        }
    }
}
