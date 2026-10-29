package com.dvarahq.agentworks.domain.sandbox;

public enum SandboxCapability {
    PROCESS_EXECUTION,
    /** Ephemeral, provider-local filesystem capture; this does not claim durable recovery. */
    FILESYSTEM_SNAPSHOT_RESTORE,
    /** Isolation suitable for executing code that is not trusted by the host process. */
    HOSTILE_CODE_ISOLATION,
    DIGEST_PINNED_IMAGE,
    NON_ROOT_IDENTITY,
    CPU_LIMIT,
    MEMORY_LIMIT,
    PID_LIMIT,
    NETWORK_EGRESS_CONTROL,
    /** Restart-safe restore whose durable ownership and fencing contract is externally evidenced. */
    DURABLE_SNAPSHOT_RESTORE
}
