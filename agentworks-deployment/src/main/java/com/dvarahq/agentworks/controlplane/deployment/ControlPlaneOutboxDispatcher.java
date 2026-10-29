package com.dvarahq.agentworks.controlplane.deployment;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleJson;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Delivers canonical {@code control_plane_outbox} events to a consumer (ADR-008, master §31.1). Outbox rows stay
 * append-only; each consumer records every row it received in {@code control_plane_outbox_delivery}, in the
 * transaction that delivered it, so a crash redelivers and consumers must be idempotent.
 *
 * <p>Delivery is tracked per row, not by a high-water sequence. Outbox sequence numbers are allocated before their
 * transactions commit, so a row with a lower sequence can become visible after a higher one was delivered; a
 * high-water cursor would skip it forever. Here any committed row that has no delivery record is delivered on the
 * next dispatch, whatever its sequence.
 *
 * <p>A notification is only a hint that names what changed: consumers always re-read authoritative PostgreSQL state.
 * A Run Plane node that restarts with empty observations calls {@link #announceDeployments} once to re-read every
 * deployment, because rows it already received are not redelivered. The recovering dispatch path isolates a poison
 * row, backs it off using database time, and quarantines it after a bounded number of attempts (#44).
 */
public final class ControlPlaneOutboxDispatcher {
    private static final Pattern CONSUMER = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");

    /**
     * A minimized notification; the payload is kept only for identities, never trusted as state. A startup
     * announcement has no outbox event: its {@code eventId} is null, its sequence 0, and its type
     * {@code DEPLOYMENT_STATE}.
     */
    public record Notification(UUID eventId, long sequence, String eventType, UUID workspaceId, UUID deploymentId,
            Long generation, UUID releaseId, Instant createdAt) {
        public Notification(UUID eventId, long sequence, String eventType, UUID workspaceId, UUID deploymentId,
                Long generation) {
            this(eventId, sequence, eventType, workspaceId, deploymentId, generation, null, null);
        }
        public Notification(UUID eventId, long sequence, String eventType, UUID workspaceId, UUID deploymentId,
                Long generation, Instant createdAt) {
            this(eventId, sequence, eventType, workspaceId, deploymentId, generation, null, createdAt);
        }
    }

    /** Bounded retry policy. The attempt that reaches {@code maxAttempts} quarantines the row. */
    public record RetryPolicy(Duration initialBackoff, Duration maxBackoff, int maxAttempts) {
        public static final RetryPolicy DEFAULT = new RetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), 5);

        public RetryPolicy {
            Objects.requireNonNull(initialBackoff, "initialBackoff");
            Objects.requireNonNull(maxBackoff, "maxBackoff");
            if (initialBackoff.isZero() || initialBackoff.isNegative()
                    || maxBackoff.compareTo(initialBackoff) < 0 || maxAttempts < 1 || maxAttempts > 100) {
                throw new IllegalArgumentException("outbox retry policy is invalid");
            }
        }
    }

    /** One recovery pass; failures never prevent later ready rows in the same pass. */
    public record DispatchReport(int attempted, int delivered, int failed, int quarantined) {}

    /** Privacy-safe operator view of a poison record. */
    public record Quarantined(UUID workspaceId, UUID outboxId, String consumer, long sequence, String eventType,
            int attempts, Instant firstFailedAt, Instant lastFailedAt, Instant quarantinedAt, String lastErrorCode,
            String lastErrorDigest, int releaseCount, Instant lastReleasedAt) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final com.dvarahq.agentworks.storage.lifecycle.KeyLocks locks;
    /** The H2 database this dispatcher's data source connects to, once read. */
    private volatile String database;

    public ControlPlaneOutboxDispatcher(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(transactions, "transactions");
        // Every dispatch is a transaction of its own, whatever the caller runs in (#201 review): joined to a caller's
        // REPEATABLE READ or SERIALIZABLE transaction, its batch would be read from a snapshot older than its lock.
        // Its isolation is READ COMMITTED whatever the injected template's (#206): under REPEATABLE READ or
        // SERIALIZABLE, a PostgreSQL snapshot is taken at the transaction's first statement, the lock itself, so rows
        // committed while this dispatch waited for another's lock would stay invisible to its batch read. The
        // supported JDBC transaction manager must accept an isolation level: verifyIsolation() proves it at startup
        // (issues #208, #210, and #217). The server auto-configuration rejects JPA and JTA managers before this.
        var manager = Objects.requireNonNull(transactions.getTransactionManager(), "transactionManager");
        this.transactions = new TransactionTemplate(manager, transactions);
        this.transactions.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.locks = new com.dvarahq.agentworks.storage.lifecycle.KeyLocks(jdbc);
    }

    /**
     * Begins and rolls back one transaction as a dispatch does, at READ COMMITTED, so a transaction manager that
     * refuses the isolation level fails at startup rather than at every dispatch (PR #208 review). The server calls it
     * once, when it creates the dispatcher. It takes a pooled connection and writes nothing.
     *
     * @throws IllegalStateException when the transaction manager refuses READ COMMITTED
     */
    public void verifyIsolation() {
        try {
            transactions.executeWithoutResult(org.springframework.transaction.TransactionStatus::setRollbackOnly);
        } catch (org.springframework.transaction.InvalidIsolationLevelException refused) {
            throw new IllegalStateException("the outbox dispatch runs at READ COMMITTED, which the transaction "
                    + "manager refuses", refused);
        }
    }

    /**
     * Delivers up to {@code batchSize} undelivered events, oldest sequence first, and records their delivery in the
     * same transaction. If {@code sink} throws, nothing is recorded and the batch is redelivered. The transaction is
     * always the dispatch's own: a caller's transaction, if any, is suspended meanwhile and neither delivers nor
     * records the batch, and a failing sink never rolls the caller's work back.
     *
     * <p>Inside a caller's transaction, the dispatch therefore needs a second pooled connection while the caller's is
     * held, and nothing the caller holds is released for it (#206): a caller that holds row locks, or the pool's last
     * connection, that the dispatch or its {@code sink} needs waits on itself until a lock or pool timeout fails the
     * dispatch. Call it outside a transaction, or from one that holds no lock the dispatch or its sink needs, with a
     * pool of at least two connections per concurrent caller.
     */
    public int dispatch(String consumer, int batchSize, Consumer<Notification> sink) {
        String name = consumer(consumer);
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batch size is 1 to 1000");
        Objects.requireNonNull(sink, "sink");
        // Dispatches of one consumer are serialized (#175), so two dispatchers never deliver the same batch. On
        // PostgreSQL a transaction-scoped advisory lock is taken first in the dispatch's own transaction, before the
        // batch is read, and held until that transaction commits or rolls back, the sink's work included. H2 (one
        // process, local development) has no such lock: a lock per database and consumer is held across the
        // dispatch's transaction, which also keeps a duplicate uncommitted delivery insert from waiting out H2's lock
        // timeout.
        if (postgres()) return dispatchLocked(name, batchSize, sink);
        java.util.concurrent.locks.ReentrantLock local = localLock(name);
        local.lock();
        try {
            return dispatchLocked(name, batchSize, sink);
        } finally {
            local.unlock();
        }
    }

    /**
     * Delivers every currently ready row independently. A failing row receives a database-time retry schedule and
     * does not block later rows; its exception class and a one-way digest are retained, never its message or payload.
     * The sink must be idempotent because a process can still die after the effect and before delivery is recorded.
     */
    public DispatchReport dispatchRecovering(String consumer, int batchSize, RetryPolicy policy,
            Consumer<Notification> sink) {
        String name = consumer(consumer);
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batch size is 1 to 1000");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(sink, "sink");
        if (postgres()) return dispatchRecoveringLocked(name, batchSize, policy, sink);
        java.util.concurrent.locks.ReentrantLock local = localLock(name);
        local.lock();
        try {
            return dispatchRecoveringLocked(name, batchSize, policy, sink);
        } finally {
            local.unlock();
        }
    }

    private DispatchReport dispatchRecoveringLocked(String name, int batchSize, RetryPolicy policy,
            Consumer<Notification> sink) {
        DispatchReport result = transactions.execute(status -> {
            locks.lock("control-plane-outbox-dispatch:" + name);
            List<Notification> batch = jdbc.query("select o.id, o.workspace_id, o.sequence_number, o.event_type, "
                            + "o.payload_json, o.created_at from control_plane_outbox o left join "
                            + "control_plane_outbox_attempt a on a.consumer = ? and a.workspace_id = o.workspace_id "
                            + "and a.outbox_id = o.id where not exists (select 1 from control_plane_outbox_delivery d "
                            + "where d.consumer = ? and d.workspace_id = o.workspace_id and d.outbox_id = o.id) "
                            + "and a.quarantined_at is null and (a.next_attempt_at is null or "
                            + "a.next_attempt_at <= current_timestamp) order by o.sequence_number fetch first "
                            + batchSize + " rows only",
                    (rs, n) -> notification(rs.getObject("id", UUID.class), rs.getLong("sequence_number"),
                            rs.getString("event_type"), rs.getObject("workspace_id", UUID.class),
                            rs.getString("payload_json"), rs.getTimestamp("created_at").toInstant()), name, name);
            int delivered = 0;
            int failed = 0;
            int quarantined = 0;
            for (Notification notification : batch) {
                try {
                    sink.accept(notification);
                    record(name, notification);
                    delivered++;
                } catch (RuntimeException failure) {
                    failed++;
                    if (recordFailure(name, notification, failure, policy)) quarantined++;
                }
            }
            return new DispatchReport(batch.size(), delivered, failed, quarantined);
        });
        return result == null ? new DispatchReport(0, 0, 0, 0) : result;
    }

    /** Committed poison rows for one workspace, oldest quarantine first. */
    public List<Quarantined> quarantined(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace,
            String consumer) {
        String name = consumer(consumer);
        return jdbc.query("select a.workspace_id, a.outbox_id, a.consumer, o.sequence_number, o.event_type, "
                        + "a.attempts, a.first_failed_at, a.last_failed_at, a.quarantined_at, a.last_error_code, "
                        + "a.last_error_digest, a.release_count, a.last_released_at from control_plane_outbox_attempt a "
                        + "join control_plane_outbox o on o.workspace_id = a.workspace_id and o.id = a.outbox_id "
                        + "where a.workspace_id = ? and a.consumer = ? and a.quarantined_at is not null "
                        + "order by a.quarantined_at, a.outbox_id",
                (rs, n) -> new Quarantined(rs.getObject("workspace_id", UUID.class),
                        rs.getObject("outbox_id", UUID.class), rs.getString("consumer"),
                        rs.getLong("sequence_number"), rs.getString("event_type"), rs.getInt("attempts"),
                        rs.getTimestamp("first_failed_at").toInstant(), rs.getTimestamp("last_failed_at").toInstant(),
                        rs.getTimestamp("quarantined_at").toInstant(), rs.getString("last_error_code"),
                        rs.getString("last_error_digest"), rs.getInt("release_count"),
                        rs.getTimestamp("last_released_at") == null ? null
                                : rs.getTimestamp("last_released_at").toInstant()), workspace.value(), name);
    }

    /** Releases exactly one workspace-scoped poison row for an immediate retry. */
    public boolean release(com.dvarahq.agentworks.domain.tenant.WorkspaceId workspace, String consumer, UUID outboxId) {
        Objects.requireNonNull(outboxId, "outboxId");
        return jdbc.update("update control_plane_outbox_attempt set next_attempt_at = current_timestamp, "
                        + "quarantined_at = null, release_count = release_count + 1, "
                        + "last_released_at = current_timestamp "
                        + "where workspace_id = ? and consumer = ? and outbox_id = ? and quarantined_at is not null",
                workspace.value(), consumer(consumer), outboxId) == 1;
    }

    /** Poison rows for a consumer; intended for a low-cardinality gauge. */
    public long quarantined(String consumer) {
        Long count = jdbc.queryForObject("select count(*) from control_plane_outbox_attempt where consumer = ? "
                + "and quarantined_at is not null", Long.class, consumer(consumer));
        return count == null ? 0 : count;
    }

    /** Age in seconds of the oldest undelivered event, including backed-off and quarantined rows. */
    public double oldestPendingAgeSeconds(String consumer) {
        java.sql.Timestamp created = jdbc.queryForObject("select min(o.created_at) from control_plane_outbox o where "
                + "not exists (select 1 from control_plane_outbox_delivery d where d.consumer = ? and "
                + "d.workspace_id = o.workspace_id and d.outbox_id = o.id)", java.sql.Timestamp.class,
                consumer(consumer));
        if (created == null) return 0;
        java.sql.Timestamp now = jdbc.queryForObject("select current_timestamp", java.sql.Timestamp.class);
        return Math.max(0, Duration.between(created.toInstant(), Objects.requireNonNull(now).toInstant()).toMillis()
                / 1000.0);
    }

    private boolean recordFailure(String consumer, Notification notification, RuntimeException failure,
            RetryPolicy policy) {
        Integer prior = jdbc.query("select attempts from control_plane_outbox_attempt where consumer = ? and "
                        + "workspace_id = ? and outbox_id = ?",
                rs -> rs.next() ? rs.getInt(1) : null, consumer, notification.workspaceId(), notification.eventId());
        int attempts = prior == null ? 1 : prior + 1;
        java.sql.Timestamp databaseNow = Objects.requireNonNull(
                jdbc.queryForObject("select current_timestamp", java.sql.Timestamp.class));
        boolean quarantine = attempts >= policy.maxAttempts();
        Instant next = quarantine ? null : databaseNow.toInstant().plus(backoff(policy, attempts));
        String code = failure.getClass().getName();
        if (code.length() > 128) code = code.substring(0, 128);
        String detail = failure.getMessage() == null ? code : code + ":" + failure.getMessage();
        String digest = sha256(detail);
        if (prior == null) {
            jdbc.update("insert into control_plane_outbox_attempt(consumer, workspace_id, outbox_id, attempts, "
                            + "first_failed_at, last_failed_at, next_attempt_at, quarantined_at, last_error_code, "
                            + "last_error_digest) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", consumer,
                    notification.workspaceId(), notification.eventId(), attempts, databaseNow, databaseNow,
                    next == null ? null : java.sql.Timestamp.from(next), quarantine ? databaseNow : null, code, digest);
        } else {
            jdbc.update("update control_plane_outbox_attempt set attempts = ?, last_failed_at = ?, next_attempt_at = ?, "
                            + "quarantined_at = ?, last_error_code = ?, last_error_digest = ? where consumer = ? and "
                            + "workspace_id = ? and outbox_id = ?", attempts, databaseNow,
                    next == null ? null : java.sql.Timestamp.from(next), quarantine ? databaseNow : null, code, digest,
                    consumer, notification.workspaceId(), notification.eventId());
        }
        return quarantine;
    }

    private static Duration backoff(RetryPolicy policy, int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 30);
        Duration calculated;
        try {
            calculated = policy.initialBackoff().multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            calculated = policy.maxBackoff();
        }
        return calculated.compareTo(policy.maxBackoff()) > 0 ? policy.maxBackoff() : calculated;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Per-consumer dispatch locks of each H2 database in this process, where no advisory lock exists. They are keyed by
     * the database, not the data source, so every pool or proxy over one database shares them (#201). One small lock
     * is kept per database and consumer for the life of the process.
     */
    private static final Map<String, java.util.concurrent.locks.ReentrantLock> LOCAL_DISPATCH =
            new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.concurrent.locks.ReentrantLock localLock(String consumer) {
        return LOCAL_DISPATCH.computeIfAbsent(database() + '\u0000' + consumer,
                key -> new java.util.concurrent.locks.ReentrantLock());
    }

    /**
     * The H2 database's identity: its file path, or, in memory, where one name is one database per process, its name.
     */
    private String database() {
        String value = database;
        if (value == null) {
            value = jdbc.queryForObject("select case when database_path() is null then 'mem:' || database() "
                    + "else 'file:' || database_path() end", String.class);
            database = Objects.requireNonNull(value, "database");
        }
        return value;
    }

    private boolean postgres() {
        return locks.postgres();
    }

    private int dispatchLocked(String name, int batchSize, Consumer<Notification> sink) {
        Integer delivered = transactions.execute(status -> {
            locks.lock("control-plane-outbox-dispatch:" + name);
            List<Notification> batch = jdbc.query("select o.id, o.workspace_id, o.sequence_number, o.event_type, "
                            + "o.payload_json from control_plane_outbox o where not exists (select 1 from "
                            + "control_plane_outbox_delivery d where d.consumer = ? and d.workspace_id = o.workspace_id "
                            + "and d.outbox_id = o.id) order by o.sequence_number fetch first " + batchSize + " rows only",
                    (rs, n) -> notification(rs.getObject("id", UUID.class), rs.getLong("sequence_number"),
                            rs.getString("event_type"), rs.getObject("workspace_id", UUID.class),
                            rs.getString("payload_json")), name);
            for (Notification notification : batch) sink.accept(notification);
            for (Notification notification : batch) record(name, notification);
            return batch.size();
        });
        return delivered == null ? 0 : delivered;
    }

    /** Committed outbox events that {@code consumer} has not received. */
    public long pending(String consumer) {
        Long count = jdbc.queryForObject("select count(*) from control_plane_outbox o where not exists (select 1 from "
                + "control_plane_outbox_delivery d where d.consumer = ? and d.workspace_id = o.workspace_id "
                + "and d.outbox_id = o.id)", Long.class, consumer(consumer));
        return count == null ? 0 : count;
    }

    /**
     * Startup reconciliation: names every deployment with its current generation, so a restarted consumer re-reads
     * authoritative state for deployments whose events it already received. It reads current rows only and records
     * no delivery.
     */
    public int announceDeployments(Consumer<Notification> sink) {
        Objects.requireNonNull(sink, "sink");
        List<Notification> deployments = jdbc.query("select workspace_id, id, revision from agent_deployment "
                        + "order by workspace_id, id",
                (rs, n) -> new Notification(null, 0, "DEPLOYMENT_STATE", rs.getObject("workspace_id", UUID.class),
                        rs.getObject("id", UUID.class), rs.getLong("revision"), null));
        deployments.forEach(sink);
        return deployments.size();
    }

    /** Records one delivery; a concurrent dispatcher of the same consumer may have recorded it first. */
    private void record(String consumer, Notification notification) {
        String insert = "insert into control_plane_outbox_delivery(consumer, workspace_id, outbox_id, delivered_at) "
                + "values (?, ?, ?, current_timestamp)";
        if (postgres()) {
            jdbc.update(insert + " on conflict do nothing", consumer, notification.workspaceId(), notification.eventId());
        } else {
            try {
                jdbc.update(insert, consumer, notification.workspaceId(), notification.eventId());
            } catch (DuplicateKeyException alreadyRecorded) {
                // At-least-once delivery: the other dispatcher's record stands.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Notification notification(UUID id, long sequence, String type, UUID workspace, String payload) {
        return notification(id, sequence, type, workspace, payload, null);
    }

    private static Notification notification(UUID id, long sequence, String type, UUID workspace, String payload,
            Instant createdAt) {
        Map<String, Object> values = LifecycleJson.read(payload, Map.class);
        Object deployment = values.get("deploymentId");
        Object generation = values.get("generation");
        Object release = values.get("releaseId");
        return new Notification(id, sequence, type, workspace,
                deployment == null ? null : UUID.fromString(deployment.toString()),
                generation instanceof Number number ? number.longValue() : null,
                release == null ? null : UUID.fromString(release.toString()), createdAt);
    }

    private static String consumer(String consumer) {
        if (consumer == null || !CONSUMER.matcher(consumer).matches()) {
            throw new IllegalArgumentException("a consumer name is 1 to 64 characters of [a-z0-9.-]");
        }
        return consumer;
    }
}
