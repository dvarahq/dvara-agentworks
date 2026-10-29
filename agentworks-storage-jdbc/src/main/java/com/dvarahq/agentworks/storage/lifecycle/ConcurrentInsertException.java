package com.dvarahq.agentworks.storage.lifecycle;

import org.springframework.dao.TransientDataAccessException;

/**
 * A concurrent transaction committed the same unique lifecycle identity first, such as an idempotency key or a
 * deployment name. Retrying the whole transaction observes the winner and replays or conflicts deterministically.
 */
public final class ConcurrentInsertException extends TransientDataAccessException {
    public ConcurrentInsertException(Throwable cause) {
        super("a concurrent transaction committed the same lifecycle identity", cause);
    }
}
