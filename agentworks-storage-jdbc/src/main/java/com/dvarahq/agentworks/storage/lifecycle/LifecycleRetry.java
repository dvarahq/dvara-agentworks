package com.dvarahq.agentworks.storage.lifecycle;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded retry for whole lifecycle transactions after deadlock, serialization, lock-timeout, or idempotency
 * races. Each attempt is a complete transaction, so exhaustion leaves no partial commitment.
 */
public final class LifecycleRetry {
    public static final int DEFAULT_ATTEMPTS = 4;
    /** Serialization failure, deadlock, and lock-not-available (lock timeout) in PostgreSQL. */
    private static final Set<String> TRANSIENT_SQL_STATES = Set.of("40001", "40P01", "55P03");
    private final int attempts;
    private final Consumer<DataAccessException> observer;

    public LifecycleRetry(int attempts) {
        this(attempts, retried -> { });
    }

    /** {@code observer} sees each failure that is about to be retried, for metrics and tests. */
    public LifecycleRetry(int attempts, Consumer<DataAccessException> observer) {
        if (attempts < 1) throw new IllegalArgumentException("attempts must be positive");
        this.attempts = attempts;
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public static LifecycleRetry standard() {
        return new LifecycleRetry(DEFAULT_ATTEMPTS);
    }

    /**
     * Runs {@code transaction}, retrying transient failures. Inside an already active transaction nothing is retried:
     * a failed statement dooms the enclosing transaction, so the caller that owns it retries the whole unit.
     */
    public <T> T execute(String operation, Supplier<T> transaction) {
        Objects.requireNonNull(transaction, "transaction");
        if (TransactionSynchronizationManager.isActualTransactionActive()) return transaction.get();
        DataAccessException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return transaction.get();
            } catch (DataAccessException exception) {
                if (!retryable(exception)) throw exception;
                last = exception;
                if (attempt == attempts) break;
                observer.accept(exception);
                backoff(attempt);
            }
        }
        throw new LifecycleRetryExhaustedException(operation, attempts, last);
    }

    static boolean retryable(DataAccessException exception) {
        if (exception instanceof ConcurrencyFailureException || exception instanceof ConcurrentInsertException) return true;
        // Spring does not translate every PostgreSQL state (for example 55P03 lock_timeout); classify by SQLSTATE.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && TRANSIENT_SQL_STATES.contains(sql.getSQLState())) return true;
        }
        return false;
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(200L, 10L * (1L << attempt)) + (long) (Math.random() * 10));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during lifecycle retry", interrupted);
        }
    }
}
