package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleConflictException;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.Idempotent;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.IdempotencyDigests;
import com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository;
import com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.IdempotencyRow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves scoped idempotency before any mutation (ADR-027). The transaction takes the namespace lock, then a
 * per-key lock, and only then looks the key up: a matching key and fingerprint replays the original result, a
 * mismatch conflicts, a compacted result returns {@code IDEMPOTENCY_RESULT_EXPIRED}, and a retired namespace rejects
 * admission. A miss executes only when no record in the namespace was digested with a key version that is no
 * longer configured; otherwise the request fails closed with {@code IDEMPOTENCY_KEY_VERSION_RETIRED}. The mutation and its result record commit atomically. A business conflict rolls the transaction back;
 * it is never caught and replayed inside a transaction that may already hold writes. A concurrent loser that still
 * collides on the unique key rolls back and replays in a fresh transaction through the bounded retry.
 */
final class LifecycleIdempotency {
    private final TenantTransactionTemplate tenants;
    private final JdbcIdempotencyRepository records;
    private final DatabaseClock clock;
    private final LifecycleRetry retry;
    private final Duration replayWindow;
    private final IdempotencyDigests digests;

    LifecycleIdempotency(TenantTransactionTemplate tenants, JdbcIdempotencyRepository records, DatabaseClock clock,
            LifecycleRetry retry, Duration replayWindow, IdempotencyDigests digests) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.records = Objects.requireNonNull(records, "records");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.replayWindow = Objects.requireNonNull(replayWindow, "replayWindow");
        this.digests = digests;
    }

    <T> Idempotent<T> execute(WorkspaceId workspace, String namespace, String key, Object fingerprintSource,
            Class<T> type, Function<T, UUID> resultId, Function<T, String> resultStatus, Supplier<T> mutation) {
        if (key == null) {
            return new Idempotent<>(retry.execute(namespace, () -> tenants.execute(workspace, mutation)), false);
        }
        if (key.isBlank() || key.length() > 255) {
            throw new LifecycleConflictException("INVALID_IDEMPOTENCY_KEY", "idempotency keys are 1 to 255 characters");
        }
        if (digests == null) {
            throw new LifecycleConflictException("IDEMPOTENCY_UNAVAILABLE",
                    "idempotency keys require configured HMAC keys (agentworks.idempotency.hmac-keys)");
        }
        String scope = workspace.value().toString();
        String canonical = LifecycleJson.write(fingerprintSource);
        IdempotencyDigests.Digest active = digests.key(scope, namespace, key);
        List<String> candidates = digests.keyCandidates(scope, namespace, key).stream()
                .map(IdempotencyDigests.Digest::hex).toList();
        tenants.executeWithoutResult(workspace, () -> records.ensureNamespace(namespace));
        return retry.execute(namespace, () -> tenants.execute(workspace, () -> {
            if (!records.lockNamespaceOpen(namespace)) {
                throw new LifecycleConflictException("IDEMPOTENCY_NAMESPACE_RETIRED",
                        "this operation namespace is retired and admits no requests");
            }
            records.lockKey(namespace, digests.lockScope(namespace, key));
            var existing = records.find(namespace, candidates);
            if (existing.isPresent()) return replay(existing.get(), scope, namespace, canonical, type);
            if (records.holdsUnretainedVersion(namespace, digests.retainedVersions())) throw retired();
            T result = mutation.get();
            Instant now = clock.now();
            String fingerprint = digests.fingerprint(active.version(), scope, namespace, canonical).orElseThrow();
            records.record(new IdempotencyRow(namespace, active.hex(), fingerprint, active.version(),
                    type.getSimpleName(), resultId.apply(result), resultStatus.apply(result),
                    LifecycleJson.write(result), now, now.plus(replayWindow), null));
            return new Idempotent<>(result, false);
        }));
    }

    private <T> Idempotent<T> replay(IdempotencyRow row, String scope, String namespace, String canonical, Class<T> type) {
        String fingerprint = digests.fingerprint(row.digestVersion(), scope, namespace, canonical)
                .orElseThrow(LifecycleIdempotency::retired);
        if (!row.fingerprintDigest().equals(fingerprint)) {
            throw new LifecycleConflictException("IDEMPOTENCY_KEY_REUSED",
                    "the idempotency key was already used for a different request");
        }
        if (row.resultJson() == null) {
            throw new LifecycleConflictException("IDEMPOTENCY_RESULT_EXPIRED",
                    "the original result was compacted after its replay window", row.resultId());
        }
        return new Idempotent<>(LifecycleJson.read(row.resultJson(), type), true);
    }

    private static LifecycleConflictException retired() {
        return new LifecycleConflictException("IDEMPOTENCY_KEY_VERSION_RETIRED",
                "records written under a retired HMAC key version remain, so a replay cannot be ruled out");
    }

    int compact(WorkspaceId workspace) {
        return tenants.execute(workspace, () -> records.compactExpired(clock.now()));
    }

    /** Terminal: closes admission for the namespace. Tombstones may be purged only afterwards. */
    boolean retireNamespace(WorkspaceId workspace, String namespace) {
        tenants.executeWithoutResult(workspace, () -> records.ensureNamespace(namespace));
        return tenants.execute(workspace, () -> records.retireNamespace(namespace, clock.now()));
    }

    int purgeRetiredNamespace(WorkspaceId workspace, String namespace) {
        return tenants.execute(workspace, () -> records.purgeRetiredNamespace(namespace));
    }
}
