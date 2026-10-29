package com.dvarahq.agentworks.identity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Password verifications are bounded per node so a burst cannot exhaust Argon2 memory. */
class HashingBulkheadTest {

    @Test
    void refusesWorkBeyondItsSlotsAndReleasesThem() throws Exception {
        var bulkhead = new HashingBulkhead(1);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var busy = executor.submit(() -> bulkhead.run(() -> {
                holding.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return "done";
            }));
            holding.await();

            assertThatThrownBy(() -> bulkhead.run(() -> "second")).isInstanceOfSatisfying(IdentityException.class,
                    failure -> assertThat(failure.code()).isEqualTo(IdentityException.TOO_MANY_ATTEMPTS));
            release.countDown();
            assertThat(busy.get()).isEqualTo("done");
        }
        assertThat(bulkhead.available()).isOne();
        assertThatThrownBy(() -> bulkhead.run(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.available()).as("a failing verification releases its slot").isOne();
        assertThat(bulkhead.run(() -> "again")).isEqualTo("again");
    }
}
