package com.dvarahq.agentworks.sdk.extension;

import java.util.concurrent.atomic.AtomicBoolean;

/** Framework-neutral cancellation signal for provider calls and TCKs. */
@FunctionalInterface
public interface CancellationSignal {
    boolean cancelled();

    static Mutable mutable() { return new Mutable(); }

    final class Mutable implements CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public void cancel() { cancelled.set(true); }
        @Override public boolean cancelled() { return cancelled.get(); }
    }
}
