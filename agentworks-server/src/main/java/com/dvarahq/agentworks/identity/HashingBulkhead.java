package com.dvarahq.agentworks.identity;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Bounds how many password verifications hash at once on this node. Each Argon2id verification holds 19 MiB, so an
 * unbounded burst of sign-in attempts from many addresses could exhaust memory. A caller waits briefly for a slot and
 * is otherwise refused with {@code TOO_MANY_ATTEMPTS}, before any password is examined.
 */
public final class HashingBulkhead {
    static final Duration WAIT = Duration.ofSeconds(2);

    private final Semaphore slots;

    public HashingBulkhead(int permits) {
        this.slots = new Semaphore(permits, true);
    }

    <T> T run(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new IdentityException(IdentityException.TOO_MANY_ATTEMPTS,
                    "Too many sign-in attempts are in progress; retry later.");
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }

    int available() {
        return slots.availablePermits();
    }
}
