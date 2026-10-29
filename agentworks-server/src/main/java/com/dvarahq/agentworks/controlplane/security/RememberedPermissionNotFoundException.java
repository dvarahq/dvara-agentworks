package com.dvarahq.agentworks.controlplane.security;

import java.util.UUID;

public final class RememberedPermissionNotFoundException extends RuntimeException {
    public RememberedPermissionNotFoundException(UUID id) {
        super("Remembered permission " + id + " was not found.");
    }
}
