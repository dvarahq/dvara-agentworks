package com.dvarahq.agentworks.storage.memory;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.memory.AgentMemoryStore;
import com.dvarahq.agentworks.memory.MemoryActor;
import com.dvarahq.agentworks.memory.MemoryKind;
import com.dvarahq.agentworks.memory.MemoryQuery;
import com.dvarahq.agentworks.memory.MemoryRecord;
import com.dvarahq.agentworks.memory.MemoryScope;
import com.dvarahq.agentworks.memory.MemoryWrite;
import com.dvarahq.agentworks.storage.TenantContext;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.Digests;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Spring JDBC implementation of the Core memory contract. No JPA/JTA or provider-specific storage is involved. */
public final class JdbcAgentMemoryStore implements AgentMemoryStore {
    private static final UUID SYSTEM_ACTOR = new UUID(0, 0);
    private static final String COLUMNS = "id, workspace_id, definition_id, memory_kind, scope_id, session_id, "
            + "run_id, release_id, revision_id, role, content_text, content_digest, created_at, expires_at";
    private final JdbcTemplate jdbc;
    private final TenantTransactionTemplate tenants;

    public JdbcAgentMemoryStore(JdbcTemplate jdbc, TenantTransactionTemplate tenants) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Override
    public List<MemoryRecord> read(MemoryActor actor, MemoryQuery query) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(query, "query");
        return tenants.execute(workspace(query.scope()), () -> {
            verifyActor(actor);
            validateScope(query.kind(), query.scope());
            Instant now = databaseNow();
            List<MemoryRecord> records = jdbc.query("select " + COLUMNS + " from agent_memory_record where "
                            + "workspace_id = ? and memory_kind = ? and scope_id = ? and expires_at > ? "
                            + "order by created_at desc, id desc fetch first " + query.limit() + " rows only",
                    (rs, row) -> record(rs, query.includeContent()), scope(), query.kind().name(),
                    scopeId(query.kind(), query.scope()), timestamp(now));
            provenance(query.scope().runId(), query.kind(), scopeId(query.kind(), query.scope()), "READ", records,
                    actor.delegatedSubjectId(), now);
            return List.copyOf(records);
        });
    }

    @Override
    public List<MemoryRecord> commit(MemoryActor actor, List<MemoryWrite> writes) {
        Objects.requireNonNull(actor, "actor");
        writes = List.copyOf(Objects.requireNonNull(writes, "writes"));
        if (writes.isEmpty() || writes.size() > 16) throw new IllegalArgumentException("writes must contain 1 to 16 items");
        UUID workspace = writes.getFirst().scope().workspaceId();
        for (MemoryWrite write : writes) {
            if (!workspace.equals(write.scope().workspaceId())) throw new IllegalArgumentException("mixed workspaces");
        }
        List<MemoryWrite> batch = writes;
        return tenants.execute(new WorkspaceId(workspace), () -> {
            verifyActor(actor);
            Instant now = databaseNow();
            List<StoredWrite> stored = new ArrayList<>();
            for (MemoryWrite write : batch) stored.add(upsert(write, now));
            List<MemoryRecord> records = stored.stream().map(StoredWrite::record).toList();
            stored.stream().filter(StoredWrite::inserted).map(StoredWrite::record)
                    .collect(java.util.stream.Collectors.groupingBy(record ->
                            new ScopeKey(record.kind(), scopeId(record.kind(), record.scope()))))
                    .forEach((key, affected) -> {
                if (affected.isEmpty()) return;
                MemoryScope memoryScope = affected.getFirst().scope();
                bump(key.kind(), memoryScope, now);
                provenance(memoryScope.runId(), key.kind(), key.scopeId(), "WRITE", affected,
                        actor.delegatedSubjectId(), now);
            });
            return List.copyOf(records);
        });
    }

    @Override
    public int delete(MemoryActor actor, MemoryKind kind, MemoryScope memoryScope) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(memoryScope, "scope").require(kind);
        return tenants.execute(workspace(memoryScope), () -> {
            verifyActor(actor);
            validateScope(kind, memoryScope);
            UUID id = scopeId(kind, memoryScope);
            List<MemoryRecord> records = jdbc.query("select " + COLUMNS + " from agent_memory_record where "
                    + "workspace_id = ? and memory_kind = ? and scope_id = ?", (rs, row) -> record(rs, false),
                    scope(), kind.name(), id);
            int changed = jdbc.update("delete from agent_memory_record where workspace_id = ? and memory_kind = ? "
                    + "and scope_id = ?", scope(), kind.name(), id);
            Instant now = databaseNow();
            if (changed > 0) bump(kind, memoryScope, now);
            provenance(memoryScope.runId(), kind, id, "DELETE", records, actor.delegatedSubjectId(), now);
            return changed;
        });
    }

    @Override
    public int expire(UUID workspaceId, Instant cutoff, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return tenants.execute(new WorkspaceId(workspaceId), () -> {
            Instant now = databaseNow();
            Instant effectiveCutoff = cutoff.isAfter(now) ? now : cutoff;
            List<MemoryRecord> expired = jdbc.query("select " + COLUMNS + " from agent_memory_record where "
                            + "workspace_id = ? and expires_at <= ? order by expires_at, id fetch first " + limit + " rows only",
                    (rs, row) -> record(rs, false), scope(), timestamp(effectiveCutoff));
            for (MemoryRecord record : expired) {
                jdbc.update("delete from agent_memory_record where workspace_id = ? and id = ?", scope(), record.id());
                bump(record.kind(), record.scope(), now);
                provenance(record.scope().runId(), record.kind(), scopeId(record.kind(), record.scope()), "EXPIRE",
                        List.of(record), SYSTEM_ACTOR, now);
            }
            return expired.size();
        });
    }

    @Override
    public long revision(MemoryKind kind, MemoryScope memoryScope) {
        Objects.requireNonNull(memoryScope, "scope").require(kind);
        return tenants.execute(workspace(memoryScope), () -> {
            validateScope(kind, memoryScope);
            return jdbc.query("select revision from agent_memory_scope_revision where workspace_id = ? and "
                            + "memory_kind = ? and scope_id = ?", (rs, row) -> rs.getLong(1), scope(), kind.name(),
                    scopeId(kind, memoryScope)).stream().findFirst().orElse(0L);
        });
    }

    @Override
    public Optional<UUID> logicalSessionId(UUID workspaceId, UUID runId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(runId, "runId");
        return tenants.execute(new WorkspaceId(workspaceId), () -> jdbc.query("select session_id from agent_session_run "
                        + "where workspace_id = ? and run_id = ?", (rs, row) -> rs.getObject(1, UUID.class),
                scope(), runId).stream().findFirst());
    }

    private StoredWrite upsert(MemoryWrite write, Instant now) {
        Long pinned = jdbc.queryForObject("select count(*) from agent_run where workspace_id = ? and id = ? and "
                        + "definition_id = ? and release_id = ? and revision_id = ?", Long.class, scope(),
                write.scope().runId(), write.scope().definitionId(), write.releaseId(), write.revisionId());
        if (pinned == null || pinned != 1) throw new SecurityException("memory write does not match the run pins");
        if (write.kind() == MemoryKind.CONVERSATION && jdbc.queryForObject("select count(*) from agent_session_run "
                        + "where workspace_id = ? and session_id = ? and run_id = ?", Long.class, scope(),
                write.scope().sessionId(), write.scope().runId()) != 1) {
            throw new SecurityException("run is not linked to the conversation session");
        }
        Optional<Stored> existing = jdbc.query("select request_digest, " + COLUMNS + " from agent_memory_record "
                        + "where workspace_id = ? and run_id = ? and idempotency_key = ?",
                (rs, row) -> new Stored(rs.getString("request_digest"), record(rs, true)), scope(),
                write.scope().runId(), write.idempotencyKey()).stream().findFirst();
        String requestDigest = Digests.prefixed(String.join("\n", write.kind().name(), write.role(), write.content(),
                write.retention().toString(), write.releaseId().toString(), write.revisionId().toString()));
        if (existing.isPresent()) {
            if (!requestDigest.equals(existing.orElseThrow().requestDigest())) {
                throw new IllegalStateException("MEMORY_IDEMPOTENCY_CONFLICT");
            }
            return new StoredWrite(existing.orElseThrow().record(), false);
        }
        UUID id = UUID.randomUUID();
        UUID scoped = scopeId(write.kind(), write.scope());
        String contentDigest = Digests.prefixed(write.content());
        Instant expires = now.plus(write.retention());
        try {
            jdbc.update("insert into agent_memory_record(workspace_id, id, definition_id, memory_kind, scope_id, "
                            + "session_id, run_id, release_id, revision_id, role, idempotency_key, request_digest, "
                            + "content_text, content_digest, created_at, expires_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    scope(), id, write.scope().definitionId(), write.kind().name(), scoped,
                    write.kind() == MemoryKind.CONVERSATION ? write.scope().sessionId() : null, write.scope().runId(),
                    write.releaseId(), write.revisionId(), write.role(), write.idempotencyKey(), requestDigest,
                    write.content(), contentDigest, timestamp(now), timestamp(expires));
        } catch (DataAccessException race) {
            MemoryRecord winner = jdbc.query("select " + COLUMNS + " from agent_memory_record where workspace_id = ? and run_id = ? "
                            + "and idempotency_key = ? and request_digest = ?", (rs, row) -> record(rs, true), scope(),
                    write.scope().runId(), write.idempotencyKey(), requestDigest).stream().findFirst().orElseThrow(() -> race);
            return new StoredWrite(winner, false);
        }
        return new StoredWrite(new MemoryRecord(id, write.kind(), write.scope(), write.releaseId(), write.revisionId(),
                write.role(), Optional.of(write.content()), contentDigest, now, expires), true);
    }

    private void bump(MemoryKind kind, MemoryScope memoryScope, Instant now) {
        UUID scoped = scopeId(kind, memoryScope);
        int changed = jdbc.update("update agent_memory_scope_revision set revision = revision + 1, updated_at = ? "
                        + "where workspace_id = ? and memory_kind = ? and scope_id = ?",
                timestamp(now), scope(), kind.name(), scoped);
        if (changed == 0) {
            try {
                jdbc.update("insert into agent_memory_scope_revision(workspace_id, memory_kind, scope_id, revision, "
                        + "updated_at) values (?, ?, ?, 1, ?)", scope(), kind.name(), scoped, timestamp(now));
            } catch (DataAccessException race) {
                jdbc.update("update agent_memory_scope_revision set revision = revision + 1, updated_at = ? where "
                        + "workspace_id = ? and memory_kind = ? and scope_id = ?", timestamp(now), scope(),
                        kind.name(), scoped);
            }
        }
    }

    private void provenance(UUID runId, MemoryKind kind, UUID scoped, String operation, List<MemoryRecord> records,
            UUID actor, Instant now) {
        String ids = records.stream().map(MemoryRecord::id).sorted(Comparator.comparing(UUID::toString))
                .map(UUID::toString).reduce((left, right) -> left + "\n" + right).orElse("");
        jdbc.update("insert into agent_memory_provenance(workspace_id, run_id, memory_kind, scope_id, operation, "
                        + "record_count, records_digest, actor_id, occurred_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                scope(), runId, kind.name(), scoped, operation, records.size(), Digests.prefixed(ids), actor,
                timestamp(now));
    }

    private MemoryRecord record(ResultSet rs, boolean includeContent) throws SQLException {
        MemoryKind kind = MemoryKind.valueOf(rs.getString("memory_kind"));
        UUID workspace = rs.getObject("workspace_id", UUID.class);
        UUID definition = rs.getObject("definition_id", UUID.class);
        UUID run = rs.getObject("run_id", UUID.class);
        UUID session = rs.getObject("session_id", UUID.class);
        return new MemoryRecord(rs.getObject("id", UUID.class), kind,
                new MemoryScope(workspace, definition, run, session), rs.getObject("release_id", UUID.class),
                rs.getObject("revision_id", UUID.class), rs.getString("role"),
                includeContent ? Optional.of(rs.getString("content_text")) : Optional.empty(),
                rs.getString("content_digest"), instant(rs, "created_at"), instant(rs, "expires_at"));
    }

    private void verifyActor(MemoryActor actor) {
        if (jdbc.queryForObject("select count(*) from workspace_membership where workspace_id = ? and subject_id = ?",
                Long.class, scope(), actor.userId()) != 1) {
            throw new SecurityException("memory actor is not in the workspace");
        }
    }

    private void validateScope(MemoryKind kind, MemoryScope memoryScope) {
        Long run = jdbc.queryForObject("select count(*) from agent_run where workspace_id = ? and id = ? and "
                        + "definition_id = ?", Long.class, scope(), memoryScope.runId(), memoryScope.definitionId());
        if (run == null || run != 1) throw new SecurityException("memory scope does not match the run");
        if (kind == MemoryKind.CONVERSATION && jdbc.queryForObject("select count(*) from agent_session_run where "
                        + "workspace_id = ? and session_id = ? and run_id = ?", Long.class, scope(),
                memoryScope.sessionId(), memoryScope.runId()) != 1) {
            throw new SecurityException("run is not linked to the conversation session");
        }
    }

    private static UUID scopeId(MemoryKind kind, MemoryScope memoryScope) {
        memoryScope.require(kind);
        return kind == MemoryKind.CONVERSATION ? memoryScope.sessionId() : memoryScope.runId();
    }

    private static WorkspaceId workspace(MemoryScope memoryScope) { return new WorkspaceId(memoryScope.workspaceId()); }
    private static UUID scope() { return TenantContext.requireWorkspace().value(); }
    private Instant databaseNow() { return jdbc.queryForObject("select current_timestamp", OffsetDateTime.class).toInstant(); }
    private static OffsetDateTime timestamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
    private record Stored(String requestDigest, MemoryRecord record) {}
    private record StoredWrite(MemoryRecord record, boolean inserted) {}
    private record ScopeKey(MemoryKind kind, UUID scopeId) {}
}
