package com.dvarahq.agentworks.sdk.extension;

/** Provider-neutral extension hook for advisory policy evaluation; Core remains the authority. */
public interface PolicyProvider {
    String id();
    PolicyDecision evaluate(ProviderInvocationContext context, String policyId, byte[] boundedInput);

    record PolicyDecision(boolean allowed, String reasonCode) {
        public PolicyDecision {
            if (reasonCode == null || reasonCode.isBlank()) throw new IllegalArgumentException("reasonCode");
        }
    }
}
