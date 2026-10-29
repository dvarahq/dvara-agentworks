package com.dvarahq.agentworks.sdk.lifecycle;

/** Durable SDK view contains references and policy identity only, never a resolved secret. */
public record CredentialPolicyRef(String credentialRef, String policyId) {
    public CredentialPolicyRef {
        credentialRef = bounded(credentialRef, "credentialRef");
        policyId = bounded(policyId, "policyId");
    }
    private static String bounded(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field);
        }
        return value;
    }
}
