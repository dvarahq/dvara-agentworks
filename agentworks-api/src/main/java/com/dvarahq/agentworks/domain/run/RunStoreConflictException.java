package com.dvarahq.agentworks.domain.run;

/** A compare-and-set write lost to a concurrent writer; nothing from the losing transaction commits. */
public final class RunStoreConflictException extends RuntimeException {
    public RunStoreConflictException(String message) {
        super(message);
    }
}
