package com.dvarahq.agentworks.controlplane.security;

import java.util.UUID;

public final class RememberedPermissionConflictException extends RuntimeException {
    public RememberedPermissionConflictException(UUID id) {
        super("Remembered permission " + id + " already has different immutable content.");
    }
}
