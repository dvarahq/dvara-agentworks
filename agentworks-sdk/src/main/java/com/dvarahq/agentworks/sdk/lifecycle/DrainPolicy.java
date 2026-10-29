package com.dvarahq.agentworks.sdk.lifecycle;

/** OSS 1.0 drain choices. Migration is explicitly unavailable under ADR-030. */
public enum DrainPolicy {
    WAIT, SUSPEND, CANCEL, REVOKE;

    public static DrainPolicy parse(String value) {
        if ("MIGRATE".equalsIgnoreCase(value) || "MIGRATE_WHEN_COMPATIBLE".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE: paused-run migration is deferred to OSS 1.1");
        }
        return valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }
}
