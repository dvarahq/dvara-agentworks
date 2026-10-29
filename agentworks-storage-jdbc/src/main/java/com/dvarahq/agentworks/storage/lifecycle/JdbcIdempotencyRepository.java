package com.dvarahq.agentworks.storage.lifecycle;

import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.IdempotencyRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.first;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.instant;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.timestamp;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.uuid;
import static com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository.workspace;

/**
 * Workspace- and namespace-scoped idempotency for lifecycle commands. Only versioned HMAC digests of keys and
 * request fingerprints are stored (see {@link IdempotencyDigests}). Compaction removes the detailed result and keeps a tombstone; tombstones can be
 * purged only after the namespace is irreversibly retired, so an expired key never becomes reusable.
 */
public final class JdbcIdempotencyRepository {
    private final JdbcTemplate jdbc;

    private final Dialect dialect;

    public JdbcIdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.dialect = new Dialect(jdbc);
    }


    /**
     * Creates the namespace row if absent. PostgreSQL uses ON CONFLICT so a concurrent creator never aborts the
     * transaction; H2 relies on the existence check.
     */
    public void ensureNamespace(String namespace) {
        if (dialect.postgres()) {
            jdbc.update("insert into idempotency_namespace(workspace_id, namespace, state) values (?, ?, 'OPEN') "
                    + "on conflict do nothing", workspace(), namespace);
            return;
        }
        jdbc.update("insert into idempotency_namespace(workspace_id, namespace, state) select ?, ?, 'OPEN' "
                + "where not exists (select 1 from idempotency_namespace where workspace_id = ? and namespace = ?)",
                workspace(), namespace, workspace(), namespace);
    }

    /**
     * Locks the namespace for admission and returns false once it is retired. First in the lock order. PostgreSQL
     * takes a shared lock so admissions proceed concurrently while retirement waits; H2 lacks FOR SHARE.
     */
    public boolean lockNamespaceOpen(String namespace) {
        String lock = dialect.shared();
        String state = first(jdbc.queryForList("select state from idempotency_namespace where workspace_id = ? "
                + "and namespace = ?" + lock, String.class, workspace(), namespace)).orElse(null);
        if (state == null) throw new IllegalStateException("idempotency namespace " + namespace + " was not created");
        return "OPEN".equals(state);
    }

    /**
     * Serializes this key's transactions on PostgreSQL; call after the namespace lock and before looking the key up.
     * {@code lockScope} must be independent of the HMAC key version.
     */
    public void lockKey(String namespace, String lockScope) {
        dialect.lockKey(workspace() + "/" + namespace + "/" + lockScope);
    }

    /**
     * True when this namespace holds a record digested with a key version outside {@code retained}. Such a record
     * cannot be matched, so a miss no longer proves that the key is unused.
     */
    public boolean holdsUnretainedVersion(String namespace, Set<Integer> retained) {
        String placeholders = String.join(", ", retained.stream().map(version -> "?").toList());
        List<Object> arguments = new ArrayList<>(List.of(workspace(), namespace));
        arguments.addAll(retained);
        return !jdbc.queryForList("select 1 from lifecycle_idempotency where workspace_id = ? and namespace = ? "
                + "and digest_version not in (" + placeholders + ") fetch first 1 rows only", Integer.class,
                arguments.toArray()).isEmpty();
    }

    /** Finds a record under any of the candidate digests of one key (retained HMAC versions and legacy). */
    public Optional<IdempotencyRow> find(String namespace, List<String> keyDigests) {
        String placeholders = String.join(", ", keyDigests.stream().map(digest -> "?").toList());
        List<Object> arguments = new ArrayList<>(List.of(workspace(), namespace));
        arguments.addAll(keyDigests);
        return first(jdbc.query("select namespace, key_digest, fingerprint_digest, digest_version, result_kind, result_id, "
                        + "result_status, result_json, created_at, replay_until, compacted_at from lifecycle_idempotency "
                        + "where workspace_id = ? and namespace = ? and key_digest in (" + placeholders + ")",
                (rs, n) -> new IdempotencyRow(rs.getString("namespace"), rs.getString("key_digest"),
                        rs.getString("fingerprint_digest"), rs.getInt("digest_version"), rs.getString("result_kind"),
                        uuid(rs, "result_id"), rs.getString("result_status"), rs.getString("result_json"),
                        instant(rs, "created_at"), instant(rs, "replay_until"), instant(rs, "compacted_at")),
                arguments.toArray()));
    }

    /** Records the result in the same transaction as the mutation it describes. */
    public void record(IdempotencyRow row) {
        try {
            jdbc.update("insert into lifecycle_idempotency(workspace_id, namespace, key_digest, fingerprint_digest, "
                            + "digest_version, result_kind, result_id, result_status, result_json, created_at, replay_until) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", workspace(), row.namespace(), row.keyDigest(),
                    row.fingerprintDigest(), row.digestVersion(), row.resultKind(), row.resultId(), row.resultStatus(), row.resultJson(),
                    timestamp(row.createdAt()), timestamp(row.replayUntil()));
        } catch (DuplicateKeyException concurrent) {
            throw new ConcurrentInsertException(concurrent);
        }
    }

    /** Replaces detailed results past their replay window with tombstones, using database time. */
    public int compactExpired(Instant now) {
        return jdbc.update("update lifecycle_idempotency set result_json = null, compacted_at = ? "
                + "where workspace_id = ? and replay_until <= ? and compacted_at is null", timestamp(now), workspace(),
                timestamp(now));
    }

    /** Terminal namespace retirement: admission closes before any tombstone may be removed. */
    public boolean retireNamespace(String namespace, Instant now) {
        return jdbc.update("update idempotency_namespace set state = 'RETIRED', retired_at = ? where workspace_id = ? "
                + "and namespace = ? and state = 'OPEN'", timestamp(now), workspace(), namespace) == 1;
    }

    /** Deletes tombstones of a retired namespace; the namespace row itself remains to prevent reuse. */
    public int purgeRetiredNamespace(String namespace) {
        Integer retired = jdbc.queryForObject("select count(*) from idempotency_namespace where workspace_id = ? "
                + "and namespace = ? and state = 'RETIRED'", Integer.class, workspace(), namespace);
        if (retired == null || retired == 0) throw new IllegalStateException("namespace must be retired before purge");
        return jdbc.update("delete from lifecycle_idempotency where workspace_id = ? and namespace = ?", workspace(),
                namespace);
    }
}
