package com.dvarahq.agentworks.gateway;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A thread-safe, one-shot cancellation signal. Callers cancel with {@link #cancel()}; Core also cancels an internal
 * signal when an invocation's deadline elapses. Adapters that advertise {@link GatewayCapability#CANCELLATION}
 * register an abort action with {@link #onCancel(Runnable)}.
 */
public final class CancellationSignal {

    /** Why an invocation stopped early. */
    public enum Reason {
        /** The caller cancelled. */
        CALLER,
        /** The invocation deadline elapsed. */
        DEADLINE,
        /** The chunk listener threw; its exception is rethrown to the caller. */
        LISTENER_FAILED
    }

    /** A registered cancellation action; closing it unregisters the action. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final AtomicReference<Reason> reason = new AtomicReference<>();
    private final List<Runnable> actions = new CopyOnWriteArrayList<>();

    private CancellationSignal() {
    }

    public static CancellationSignal create() {
        return new CancellationSignal();
    }

    /** Cancels on behalf of the caller. Idempotent; only the first reason is kept. */
    public void cancel() {
        cancel(Reason.CALLER);
    }

    boolean cancel(Reason why) {
        Objects.requireNonNull(why, "why");
        if (!reason.compareAndSet(null, why)) {
            return false;
        }
        for (Runnable action : actions) {
            runQuietly(action);
        }
        return true;
    }

    public boolean isCancelled() {
        return reason.get() != null;
    }

    public Optional<Reason> reason() {
        return Optional.ofNullable(reason.get());
    }

    /** Throws the typed failure for this signal's reason if it is cancelled. */
    public void throwIfCancelled() {
        Reason why = reason.get();
        if (why != null) {
            throw failure(why);
        }
    }

    /**
     * Runs {@code action} exactly once when this signal is cancelled, or immediately if it already is. Exceptions from
     * the action are suppressed so one faulty action cannot prevent another from aborting a request.
     */
    public Registration onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        AtomicBoolean ran = new AtomicBoolean();
        Runnable once = () -> {
            if (ran.compareAndSet(false, true)) {
                action.run();
            }
        };
        actions.add(once);
        if (isCancelled()) {
            runQuietly(once);
        }
        return () -> actions.remove(once);
    }

    static GatewayException failure(Reason why) {
        return switch (why) {
            case DEADLINE -> new GatewayException(GatewayErrorCode.TIMEOUT, "the invocation deadline elapsed");
            case CALLER, LISTENER_FAILED -> new GatewayException(GatewayErrorCode.CANCELLED,
                    "the invocation was cancelled");
        };
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // An abort action failing must not stop the remaining actions; the invocation still ends cancelled.
        }
    }
}
