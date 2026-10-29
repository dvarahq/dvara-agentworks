package com.dvarahq.agentworks.domain.security;

/** Ordered risk classification used by remembered permissions. */
public enum PermissionRisk {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /** A grant at this risk ceiling may authorize the requested risk only when it is not higher. */
    public boolean covers(PermissionRisk requested) {
        return ordinal() >= requested.ordinal();
    }
}
