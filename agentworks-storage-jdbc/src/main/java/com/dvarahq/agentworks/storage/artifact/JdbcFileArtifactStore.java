package com.dvarahq.agentworks.storage.artifact;

import com.dvarahq.agentworks.artifact.ArtifactContentScanner;
import com.dvarahq.agentworks.artifact.ArtifactLifecycle;
import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.ArtifactStoreCapability;
import com.dvarahq.agentworks.artifact.ArtifactStoreException;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactDigest;
import com.dvarahq.agentworks.domain.artifact.ArtifactLifecycleState;
import com.dvarahq.agentworks.domain.artifact.ArtifactMaintenanceReport;
import com.dvarahq.agentworks.domain.artifact.ArtifactMetadata;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactPin;
import com.dvarahq.agentworks.domain.artifact.ArtifactPinKind;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactRunBinding;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchPage;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INTEGRITY_FAILED;
import static com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INVALID_RANGE;
import static com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.LIMIT_EXCEEDED;
import static com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.NOT_FOUND_OR_DENIED;
import static com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.QUARANTINED;
import static com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.UNAVAILABLE;

/**
 * Spring JDBC metadata plus a content-addressed local/shared filesystem. Uploads are staged outside the readable
 * namespace, verified, atomically published, and only then made visible through an authoritative metadata row.
 */
public final class JdbcFileArtifactStore implements ArtifactStore, ArtifactLifecycle {
    private static final String COLUMNS = "workspace_id, id, organization_id, media_type, content_size, "
            + "content_digest, created_at, expires_at, classification, redacted, validation_state, "
            + "provenance_source, provenance_actor, run_id, release_id, revision_id, cohort_id, input_refs, "
            + "lifecycle_state, validation_reason, state_changed_at";
    private static final int BUFFER_SIZE = 64 * 1024;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Path objects;
    private final Path staging;
    private final long maximumArtifactBytes;
    private final Clock clock;
    private final ArtifactContentScanner scanner;
    private final Duration stagingLease;
    private final Duration deletionGrace;

    public JdbcFileArtifactStore(JdbcTemplate jdbc, TransactionTemplate transactions, Path root,
            long maximumArtifactBytes) {
        this(jdbc, transactions, root, maximumArtifactBytes, Clock.systemUTC(),
                new BoundedArtifactContentScanner(), Duration.ofMinutes(5), Duration.ofHours(24));
    }

    public JdbcFileArtifactStore(JdbcTemplate jdbc, TransactionTemplate transactions, Path root,
            long maximumArtifactBytes, Clock clock) {
        this(jdbc, transactions, root, maximumArtifactBytes, clock,
                new BoundedArtifactContentScanner(), Duration.ofMinutes(5), Duration.ofHours(24));
    }

    public JdbcFileArtifactStore(JdbcTemplate jdbc, TransactionTemplate transactions, Path root,
            long maximumArtifactBytes, Clock clock, ArtifactContentScanner scanner, Duration stagingLease,
            Duration deletionGrace) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.maximumArtifactBytes = maximumArtifactBytes;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.stagingLease = positive(stagingLease, "stagingLease");
        this.deletionGrace = nonNegative(deletionGrace, "deletionGrace");
        if (maximumArtifactBytes < 1) throw new IllegalArgumentException("maximum artifact bytes must be positive");
        try {
            Path canonicalRoot = initializeDirectory(Objects.requireNonNull(root, "root"));
            objects = initializeDirectory(canonicalRoot.resolve("objects"));
            staging = initializeDirectory(canonicalRoot.resolve("staging"));
        } catch (IOException failure) {
            throw new ArtifactStoreException(UNAVAILABLE);
        }
    }

    @Override
    public Set<ArtifactStoreCapability> capabilities() {
        return Set.of(ArtifactStoreCapability.RANGE_READ, ArtifactStoreCapability.SEARCH,
                ArtifactStoreCapability.EXPIRY, ArtifactStoreCapability.PINNING,
                ArtifactStoreCapability.QUARANTINE, ArtifactStoreCapability.RECONCILIATION);
    }

    @Override
    public ArtifactRef put(ArtifactWrite request) {
        Objects.requireNonNull(request, "request");
        if (!ownerExists(request.owner())) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
        if (request.declaredSize() > maximumArtifactBytes) throw new ArtifactStoreException(LIMIT_EXCEEDED);
        Instant createdAt = clock.instant();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(createdAt)) {
            throw new IllegalArgumentException("artifact expiry must be after creation");
        }
        Path partial = staging.resolve(UUID.randomUUID() + ".part");
        ArtifactDigest digest;
        ArtifactContentScanner.ScanResult scan;
        try {
            digest = stage(request, partial);
            try (InputStream content = Files.newInputStream(partial, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                scan = scanner.scan(new ArtifactContentScanner.ScanRequest(request.mediaType(),
                        request.declaredSize(), digest, content));
            }
        } catch (ArtifactStoreException failure) {
            deleteQuietly(partial);
            throw failure;
        } catch (IOException failure) {
            deleteQuietly(partial);
            throw new ArtifactStoreException(UNAVAILABLE);
        }

        ArtifactRef reference = new ArtifactRef(UUID.randomUUID());
        UUID reservation = reserve(request.owner(), digest, request.declaredSize());
        try {
            publish(request.owner(), partial, digest, request.declaredSize());
            transactions.executeWithoutResult(status -> {
                insert(reference, request, digest, createdAt, scan);
                activateReference(request.owner(), digest, reservation);
                lifecycleEvent(request.owner(), reference, "UPLOADED", request.provenance().actor(),
                        scan.validation().name(), createdAt);
            });
            return reference;
        } catch (IOException failure) {
            throw new ArtifactStoreException(UNAVAILABLE);
        } catch (RuntimeException failure) {
            // A durable STAGING reservation and any published object are reconciled after its database-time lease.
            throw failure;
        } finally {
            deleteQuietly(partial);
        }
    }

    @Override
    public ArtifactContent read(ArtifactRead request) {
        Objects.requireNonNull(request, "request");
        ArtifactMetadata metadata = find(request.owner(), request.reference());
        if (metadata == null || metadata.lifecycle() != ArtifactLifecycleState.ACTIVE || isExpired(metadata)) {
            throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
        }
        if (metadata.validation() == ArtifactValidation.QUARANTINED) throw new ArtifactStoreException(QUARANTINED);
        if (metadata.validation() != ArtifactValidation.ACCEPTED) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
        long length = request.length() == null ? metadata.size() - request.offset() : request.length();
        if (request.offset() > metadata.size() || length > metadata.size() - request.offset()) {
            throw new ArtifactStoreException(INVALID_RANGE);
        }
        Path path = contentPath(metadata.owner(), metadata.digest());
        try {
            verifyExisting(path, metadata.size(), metadata.digest());
            SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
            channel.position(request.offset());
            return new ArtifactContent(metadata, request.offset(), length, new BoundedChannelInputStream(channel,
                    length));
        } catch (ArtifactStoreException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new ArtifactStoreException(UNAVAILABLE);
        }
    }

    @Override
    public ArtifactSearchPage search(ArtifactSearchRequest request) {
        Objects.requireNonNull(request, "request");
        StringBuilder sql = new StringBuilder("select ").append(COLUMNS).append(" from artifact_object where "
                + "workspace_id = ? and organization_id = ? and validation_state = 'ACCEPTED' "
                + "and lifecycle_state = 'ACTIVE' "
                + "and (expires_at is null or expires_at > ?)");
        List<Object> arguments = new ArrayList<>();
        arguments.add(request.owner().workspaceId());
        arguments.add(request.owner().organizationId());
        arguments.add(timestamp(clock.instant()));
        if (!request.mediaTypes().isEmpty()) {
            sql.append(" and media_type in (");
            sql.append("?,".repeat(request.mediaTypes().size()));
            sql.setLength(sql.length() - 1);
            sql.append(')');
            arguments.addAll(request.mediaTypes());
        }
        if (request.classification() != null) {
            sql.append(" and classification = ?");
            arguments.add(request.classification().name());
        }
        if (request.beforeCreatedAt() != null) {
            sql.append(" and (created_at < ? or (created_at = ? and id < ?))");
            arguments.add(timestamp(request.beforeCreatedAt()));
            arguments.add(timestamp(request.beforeCreatedAt()));
            arguments.add(request.beforeId());
        }
        sql.append(" order by created_at desc, id desc fetch first ").append(request.limit() + 1).append(" rows only");
        List<ArtifactMetadata> rows = jdbc.query(sql.toString(), this::map, arguments.toArray());
        boolean hasMore = rows.size() > request.limit();
        return new ArtifactSearchPage(hasMore ? rows.subList(0, request.limit()) : rows, hasMore);
    }

    @Override
    public ArtifactMetadata metadata(ArtifactOwner owner, ArtifactRef reference) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(reference, "reference");
        ArtifactMetadata metadata = find(owner, reference);
        if (metadata == null) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
        return metadata;
    }

    @Override
    public ArtifactMetadata expire(ArtifactOwner owner, ArtifactRef reference, String actor) {
        requireActor(actor);
        return transactions.execute(status -> {
            ArtifactMetadata current = findForUpdate(owner, reference);
            if (current == null) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
            if (current.lifecycle() != ArtifactLifecycleState.ACTIVE) return current;
            Instant now = databaseNow();
            if (isPinned(owner, reference, now)) throw new ArtifactStoreException(
                    com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.PINNED);
            int changed = jdbc.update("update artifact_object set lifecycle_state = 'EXPIRED', expired_at = ?, "
                            + "state_changed_at = ? where workspace_id = ? and organization_id = ? and id = ? "
                            + "and lifecycle_state = 'ACTIVE'",
                    timestamp(now), timestamp(now), owner.workspaceId(), owner.organizationId(), reference.value());
            if (changed != 1) throw new ArtifactStoreException(
                    com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INVALID_STATE);
            lifecycleEvent(owner, reference, "EXPIRED", actor, null, now);
            return find(owner, reference);
        });
    }

    @Override
    public ArtifactMetadata transitionValidation(ArtifactOwner owner, ArtifactRef reference,
            ArtifactValidation validation, String reason, String actor) {
        Objects.requireNonNull(validation, "validation");
        if (validation == ArtifactValidation.PENDING) throw new IllegalArgumentException("validation cannot be pending");
        String why = requireReason(reason);
        requireActor(actor);
        return transactions.execute(status -> {
            ArtifactMetadata current = findForUpdate(owner, reference);
            if (current == null) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
            if (current.lifecycle() != ArtifactLifecycleState.ACTIVE) throw new ArtifactStoreException(
                    com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INVALID_STATE);
            Instant now = databaseNow();
            jdbc.update("update artifact_object set validation_state = ?, validation_reason = ?, state_changed_at = ? "
                            + "where workspace_id = ? and organization_id = ? and id = ?",
                    validation.name(), why, timestamp(now), owner.workspaceId(), owner.organizationId(),
                    reference.value());
            lifecycleEvent(owner, reference, "VALIDATION_CHANGED", actor, validation.name() + ":" + why, now);
            return find(owner, reference);
        });
    }

    @Override
    public ArtifactPin pin(ArtifactOwner owner, ArtifactRef reference, ArtifactPinKind kind, UUID subjectId,
            Instant expiresAt, String actor) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectId, "subjectId");
        requireActor(actor);
        return transactions.execute(status -> {
            ArtifactMetadata current = findForUpdate(owner, reference);
            if (current == null) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
            if (current.lifecycle() != ArtifactLifecycleState.ACTIVE) throw new ArtifactStoreException(
                    com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INVALID_STATE);
            Instant now = databaseNow();
            if (expiresAt != null && !expiresAt.isAfter(now)) {
                throw new IllegalArgumentException("artifact pin expiry must be in the future");
            }
            List<ArtifactPin> existing = jdbc.query("select workspace_id, id, artifact_id, organization_id, pin_kind, "
                            + "subject_id, created_at, expires_at, actor from artifact_pin where workspace_id = ? "
                            + "and artifact_id = ? and pin_kind = ? and subject_id = ?",
                    this::mapPin, owner.workspaceId(), reference.value(), kind.name(), subjectId);
            if (!existing.isEmpty()) return existing.getFirst();
            ArtifactPin pin = new ArtifactPin(UUID.randomUUID(), owner, reference, kind, subjectId, now, expiresAt,
                    actor);
            jdbc.update("insert into artifact_pin(workspace_id, id, artifact_id, organization_id, pin_kind, "
                            + "subject_id, created_at, expires_at, actor) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    owner.workspaceId(), pin.id(), reference.value(), owner.organizationId(), kind.name(), subjectId,
                    timestamp(now), timestamp(expiresAt), actor);
            lifecycleEvent(owner, reference, "PINNED", actor, kind.name() + ":" + subjectId, now);
            return pin;
        });
    }

    @Override
    public void unpin(ArtifactOwner owner, ArtifactRef reference, UUID pinId, String actor) {
        Objects.requireNonNull(pinId, "pinId");
        requireActor(actor);
        transactions.executeWithoutResult(status -> {
            if (findForUpdate(owner, reference) == null) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
            int changed = jdbc.update("delete from artifact_pin where workspace_id = ? and organization_id = ? "
                            + "and artifact_id = ? and id = ?", owner.workspaceId(), owner.organizationId(),
                    reference.value(), pinId);
            if (changed == 1) lifecycleEvent(owner, reference, "UNPINNED", actor, pinId.toString(), databaseNow());
        });
    }

    @Override
    public List<ArtifactPin> pins(ArtifactOwner owner, ArtifactRef reference) {
        if (find(owner, reference) == null) throw new ArtifactStoreException(NOT_FOUND_OR_DENIED);
        return List.copyOf(jdbc.query("select workspace_id, id, artifact_id, organization_id, pin_kind, subject_id, "
                        + "created_at, expires_at, actor from artifact_pin where workspace_id = ? "
                        + "and organization_id = ? and artifact_id = ? "
                        + "and (expires_at is null or expires_at > current_timestamp) order by created_at, id",
                this::mapPin, owner.workspaceId(), owner.organizationId(), reference.value()));
    }

    @Override
    public ArtifactMaintenanceReport maintain(int limit) {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("maintenance limit is invalid");
        Instant now = databaseNow();
        int stagingRemoved = removeStaleStagingFiles(now, limit);
        stagingRemoved += reconcileStaleReservations(now, Math.max(0, limit - stagingRemoved));
        jdbc.update("delete from artifact_pin where expires_at is not null and expires_at <= ?", timestamp(now));

        int integrityFailures = verifyContentObjects(limit);
        int expired = expireEligible(now, limit);
        int deleted = deleteEligibleMetadata(now, limit);
        int objectsRemoved = removeUnreferencedObjects(limit);
        ArtifactMaintenanceReport report = new ArtifactMaintenanceReport(now, stagingRemoved, expired, deleted,
                objectsRemoved, integrityFailures);
        jdbc.update("insert into artifact_maintenance_event(id, event_type, completed_at, stale_uploads_removed, "
                        + "metadata_expired, metadata_deleted, content_objects_removed, integrity_failures) "
                        + "values (?, 'MAINTENANCE_PASS', ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), timestamp(now), stagingRemoved, expired, deleted, objectsRemoved,
                integrityFailures);
        return report;
    }

    private int removeStaleStagingFiles(Instant now, int limit) {
        if (limit == 0) return 0;
        int removed = 0;
        Instant cutoff = now.minus(stagingLease);
        try (var paths = Files.list(staging)) {
            for (Path path : paths.limit(limit).toList()) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
                if (modified.toInstant().isAfter(cutoff)) continue;
                if (Files.deleteIfExists(path)) removed++;
            }
        } catch (IOException unavailable) {
            throw new ArtifactStoreException(UNAVAILABLE);
        }
        return removed;
    }

    private int reconcileStaleReservations(Instant now, int limit) {
        if (limit == 0) return 0;
        List<ContentKey> stale = jdbc.query("select organization_id, workspace_id, content_digest from artifact_content_object "
                        + "where state = 'STAGING' and lease_until <= ? order by lease_until "
                        + "fetch first " + limit + " rows only",
                (rs, row) -> new ContentKey(new ArtifactOwner(uuid(rs, "organization_id"),
                        uuid(rs, "workspace_id")), new ArtifactDigest(rs.getString("content_digest"))), timestamp(now));
        int removed = 0;
        for (ContentKey content : stale) {
            Path path = contentPath(content.owner(), content.digest());
            try {
                Files.deleteIfExists(path);
            } catch (IOException unavailable) {
                continue;
            }
            removed += jdbc.update("delete from artifact_content_object where workspace_id = ? "
                            + "and organization_id = ? and content_digest = ? and state = 'STAGING' and lease_until <= ?",
                    content.owner().workspaceId(), content.owner().organizationId(), content.digest().value(),
                    timestamp(now));
        }
        return removed;
    }

    private int verifyContentObjects(int limit) {
        List<ContentIdentity> objects = jdbc.query("select organization_id, workspace_id, content_digest, content_size "
                        + "from artifact_content_object "
                        + "where state = 'ACTIVE' order by updated_at fetch first " + limit + " rows only",
                (rs, row) -> new ContentIdentity(new ArtifactOwner(uuid(rs, "organization_id"),
                        uuid(rs, "workspace_id")), new ArtifactDigest(rs.getString("content_digest")),
                        rs.getLong("content_size")));
        int failures = 0;
        for (ContentIdentity object : objects) {
            try {
                verifyExisting(contentPath(object.owner(), object.digest()), object.size(), object.digest());
            } catch (IOException corrupt) {
                failures++;
                Instant now = databaseNow();
                jdbc.update("update artifact_object set validation_state = 'QUARANTINED', "
                                + "validation_reason = 'INTEGRITY_FAILED', state_changed_at = ? "
                                + "where workspace_id = ? and organization_id = ? and content_digest = ? "
                                + "and lifecycle_state = 'ACTIVE'",
                        timestamp(now), object.owner().workspaceId(), object.owner().organizationId(),
                        object.digest().value());
            }
        }
        return failures;
    }

    private int expireEligible(Instant now, int limit) {
        List<ArtifactKey> candidates = jdbc.query("select organization_id, workspace_id, id from artifact_object a "
                        + "where lifecycle_state = 'ACTIVE' and expires_at is not null and expires_at <= ? "
                        + "and not exists (select 1 from artifact_pin p where p.workspace_id = a.workspace_id "
                        + "and p.artifact_id = a.id and (p.expires_at is null or p.expires_at > ?)) "
                        + "and (a.run_id is null or not exists (select 1 from agent_run r where r.workspace_id = a.workspace_id "
                        + "and r.id = a.run_id and r.lifecycle not in ('SUCCEEDED','FAILED','CANCELLED'))) "
                        + "order by expires_at, id fetch first " + limit + " rows only",
                this::mapKey, timestamp(now), timestamp(now));
        int changed = 0;
        for (ArtifactKey key : candidates) {
            try {
                expire(key.owner(), key.reference(), "system:artifact-maintenance");
                changed++;
            } catch (ArtifactStoreException raced) {
                if (raced.reason() != com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.PINNED
                        && raced.reason() != com.dvarahq.agentworks.artifact.ArtifactStoreException.Reason.INVALID_STATE) {
                    throw raced;
                }
            }
        }
        return changed;
    }

    private int deleteEligibleMetadata(Instant now, int limit) {
        Instant cutoff = now.minus(deletionGrace);
        List<ArtifactKey> candidates = jdbc.query("select organization_id, workspace_id, id from artifact_object a "
                        + "where lifecycle_state = 'EXPIRED' and state_changed_at <= ? "
                        + "and not exists (select 1 from artifact_pin p where p.workspace_id = a.workspace_id "
                        + "and p.artifact_id = a.id and (p.expires_at is null or p.expires_at > ?)) "
                        + "order by state_changed_at, id fetch first " + limit + " rows only",
                this::mapKey, timestamp(cutoff), timestamp(now));
        int changed = 0;
        for (ArtifactKey key : candidates) {
            Boolean deleted = transactions.execute(status -> deleteMetadata(key, now));
            if (Boolean.TRUE.equals(deleted)) changed++;
        }
        return changed;
    }

    private boolean deleteMetadata(ArtifactKey key, Instant now) {
        ArtifactMetadata metadata = findForUpdate(key.owner(), key.reference());
        if (metadata == null || metadata.lifecycle() != ArtifactLifecycleState.EXPIRED
                || isPinned(key.owner(), key.reference(), now)) return false;
        int changed = jdbc.update("update artifact_object set lifecycle_state = 'DELETED', deleted_at = ?, "
                        + "state_changed_at = ? where workspace_id = ? and organization_id = ? and id = ? "
                        + "and lifecycle_state = 'EXPIRED'",
                timestamp(now), timestamp(now), key.owner().workspaceId(), key.owner().organizationId(),
                key.reference().value());
        if (changed != 1) return false;
        int content = jdbc.update("update artifact_content_object set reference_count = reference_count - 1, "
                        + "updated_at = ? where workspace_id = ? and organization_id = ? and content_digest = ? "
                        + "and state = 'ACTIVE' and reference_count > 0",
                timestamp(now), key.owner().workspaceId(), key.owner().organizationId(), metadata.digest().value());
        if (content != 1) throw new ArtifactStoreException(INTEGRITY_FAILED);
        lifecycleEvent(key.owner(), key.reference(), "DELETED", "system:artifact-maintenance", null, now);
        return true;
    }

    private int removeUnreferencedObjects(int limit) {
        List<ContentKey> unused = jdbc.query("select organization_id, workspace_id, content_digest "
                        + "from artifact_content_object "
                        + "where state = 'ACTIVE' and reference_count = 0 order by updated_at "
                        + "fetch first " + limit + " rows only",
                (rs, row) -> new ContentKey(new ArtifactOwner(uuid(rs, "organization_id"),
                        uuid(rs, "workspace_id")), new ArtifactDigest(rs.getString("content_digest"))));
        int removed = 0;
        for (ContentKey content : unused) {
            try {
                Files.deleteIfExists(contentPath(content.owner(), content.digest()));
            } catch (IOException unavailable) {
                continue;
            }
            removed += jdbc.update("delete from artifact_content_object where workspace_id = ? "
                            + "and organization_id = ? and content_digest = ? and state = 'ACTIVE' "
                            + "and reference_count = 0", content.owner().workspaceId(),
                    content.owner().organizationId(), content.digest().value());
        }
        return removed;
    }

    private ArtifactDigest stage(ArtifactWrite request, Path partial) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        long count = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = request.content(); OutputStream output = Files.newOutputStream(partial,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > maximumArtifactBytes) throw new ArtifactStoreException(LIMIT_EXCEEDED);
                if (count > request.declaredSize()) throw new ArtifactStoreException(INTEGRITY_FAILED);
                sha256.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        if (count != request.declaredSize()) throw new ArtifactStoreException(INTEGRITY_FAILED);
        ArtifactDigest actual = new ArtifactDigest("sha256:" + HexFormat.of().formatHex(sha256.digest()));
        if (request.expectedDigest() != null && !request.expectedDigest().equals(actual)) {
            throw new ArtifactStoreException(INTEGRITY_FAILED);
        }
        return actual;
    }

    /** Returns this writer's durable reservation token, or null when an ACTIVE object already owns the digest. */
    private UUID reserve(ArtifactOwner owner, ArtifactDigest digest, long size) {
        UUID token = UUID.randomUUID();
        for (int attempt = 0; attempt < 40; attempt++) {
            Reservation result = transactions.execute(status -> reserveOnce(owner, digest, size, token));
            if (result == Reservation.OWNED) return token;
            if (result == Reservation.ACTIVE) return null;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ArtifactStoreException(UNAVAILABLE);
            }
        }
        throw new ArtifactStoreException(UNAVAILABLE);
    }

    private Reservation reserveOnce(ArtifactOwner owner, ArtifactDigest digest, long size, UUID token) {
        Instant now = databaseNow();
        List<ContentRow> rows = jdbc.query("select content_size, state, owner_token, lease_until "
                        + "from artifact_content_object where workspace_id = ? and organization_id = ? "
                        + "and content_digest = ? for update",
                (rs, row) -> new ContentRow(rs.getLong("content_size"), rs.getString("state"),
                        uuid(rs, "owner_token"), instant(rs, "lease_until")), owner.workspaceId(),
                owner.organizationId(), digest.value());
        if (rows.isEmpty()) {
            jdbc.update("insert into artifact_content_object(workspace_id, organization_id, content_digest, "
                            + "content_size, state, reference_count, owner_token, lease_until, created_at, updated_at) "
                            + "values (?, ?, ?, ?, 'STAGING', 0, ?, ?, ?, ?)", owner.workspaceId(),
                    owner.organizationId(), digest.value(), size, token, timestamp(now.plus(stagingLease)),
                    timestamp(now), timestamp(now));
            return Reservation.OWNED;
        }
        ContentRow row = rows.getFirst();
        if (row.size() != size) throw new ArtifactStoreException(INTEGRITY_FAILED);
        if ("ACTIVE".equals(row.state())) return Reservation.ACTIVE;
        if (row.leaseUntil() != null && !row.leaseUntil().isAfter(now)) {
            jdbc.update("update artifact_content_object set owner_token = ?, lease_until = ?, updated_at = ? "
                            + "where workspace_id = ? and organization_id = ? and content_digest = ? "
                            + "and state = 'STAGING'", token, timestamp(now.plus(stagingLease)), timestamp(now),
                    owner.workspaceId(), owner.organizationId(), digest.value());
            return Reservation.OWNED;
        }
        return Reservation.BUSY;
    }

    private void activateReference(ArtifactOwner owner, ArtifactDigest digest, UUID reservation) {
        Instant now = databaseNow();
        int changed;
        if (reservation == null) {
            changed = jdbc.update("update artifact_content_object set reference_count = reference_count + 1, "
                            + "updated_at = ? where workspace_id = ? and organization_id = ? and content_digest = ? "
                            + "and state = 'ACTIVE'", timestamp(now), owner.workspaceId(), owner.organizationId(),
                    digest.value());
        } else {
            changed = jdbc.update("update artifact_content_object set state = 'ACTIVE', reference_count = 1, "
                            + "owner_token = null, lease_until = null, updated_at = ? where workspace_id = ? "
                            + "and organization_id = ? and content_digest = ? and state = 'STAGING' and owner_token = ?",
                    timestamp(now), owner.workspaceId(), owner.organizationId(), digest.value(), reservation);
        }
        if (changed != 1) throw new ArtifactStoreException(UNAVAILABLE);
    }

    private void publish(ArtifactOwner owner, Path partial, ArtifactDigest digest, long size) throws IOException {
        Path target = contentPath(owner, digest);
        Path parent = initializeDirectory(target.getParent());
        if (!parent.equals(target.getParent())) throw new IOException("artifact content directory escaped root");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyExisting(target, size, digest);
            Files.deleteIfExists(partial);
            return;
        }
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            verifyExisting(target, size, digest);
        } catch (FileAlreadyExistsException exists) {
            verifyExisting(target, size, digest);
            Files.deleteIfExists(partial);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("artifact filesystem does not support atomic publication", unsupported);
        }
    }

    private void verifyExisting(Path target, long size, ArtifactDigest expected) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) != size) {
            throw new IOException("existing content-addressed artifact is invalid");
        }
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            int read;
            while ((read = input.read(buffer)) != -1) sha256.update(buffer, 0, read);
        }
        ArtifactDigest actual = new ArtifactDigest("sha256:" + HexFormat.of().formatHex(sha256.digest()));
        if (!expected.equals(actual)) throw new IOException("content-addressed artifact digest is invalid");
    }

    private void insert(ArtifactRef reference, ArtifactWrite write, ArtifactDigest digest, Instant createdAt,
            ArtifactContentScanner.ScanResult scan) {
        ArtifactRunBinding run = write.provenance().run();
        jdbc.update("insert into artifact_object(workspace_id, id, organization_id, media_type, content_size, "
                        + "content_digest, created_at, expires_at, classification, redacted, validation_state, "
                        + "provenance_source, provenance_actor, run_id, release_id, revision_id, cohort_id, input_refs, "
                        + "lifecycle_state, validation_reason, state_changed_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                write.owner().workspaceId(), reference.value(), write.owner().organizationId(), write.mediaType(),
                write.declaredSize(), digest.value(), timestamp(createdAt), timestamp(write.expiresAt()),
                write.classification().name(), write.redacted(), scan.validation().name(), write.provenance().source(),
                write.provenance().actor(),
                run == null ? null : run.runId(), run == null ? null : run.releaseId(),
                run == null ? null : run.deploymentRevisionId(), run == null ? null : run.cohortId(),
                encodeInputs(write.provenance().inputs()), scan.reason(), timestamp(createdAt));
    }

    private ArtifactMetadata find(ArtifactOwner owner, ArtifactRef reference) {
        List<ArtifactMetadata> rows = jdbc.query("select " + COLUMNS + " from artifact_object where workspace_id = ? "
                        + "and organization_id = ? and id = ?", this::map, owner.workspaceId(), owner.organizationId(),
                reference.value());
        return rows.stream().findFirst().orElse(null);
    }

    private ArtifactMetadata findForUpdate(ArtifactOwner owner, ArtifactRef reference) {
        List<ArtifactMetadata> rows = jdbc.query("select " + COLUMNS + " from artifact_object where workspace_id = ? "
                        + "and organization_id = ? and id = ? for update", this::map, owner.workspaceId(),
                owner.organizationId(), reference.value());
        return rows.stream().findFirst().orElse(null);
    }

    private boolean isPinned(ArtifactOwner owner, ArtifactRef reference, Instant now) {
        Integer explicit = jdbc.queryForObject("select count(*) from artifact_pin where workspace_id = ? "
                        + "and organization_id = ? and artifact_id = ? and (expires_at is null or expires_at > ?)",
                Integer.class, owner.workspaceId(), owner.organizationId(), reference.value(), timestamp(now));
        if (explicit != null && explicit > 0) return true;
        Integer resumable = jdbc.queryForObject("select count(*) from artifact_object a join agent_run r "
                        + "on r.workspace_id = a.workspace_id and r.id = a.run_id where a.workspace_id = ? "
                        + "and a.organization_id = ? and a.id = ? and r.lifecycle not in ('SUCCEEDED','FAILED','CANCELLED')",
                Integer.class, owner.workspaceId(), owner.organizationId(), reference.value());
        return resumable != null && resumable > 0;
    }

    private ArtifactPin mapPin(ResultSet rs, int row) throws SQLException {
        return new ArtifactPin(uuid(rs, "id"),
                new ArtifactOwner(uuid(rs, "organization_id"), uuid(rs, "workspace_id")),
                new ArtifactRef(uuid(rs, "artifact_id")), ArtifactPinKind.valueOf(rs.getString("pin_kind")),
                uuid(rs, "subject_id"), instant(rs, "created_at"), instant(rs, "expires_at"), rs.getString("actor"));
    }

    private ArtifactKey mapKey(ResultSet rs, int row) throws SQLException {
        return new ArtifactKey(new ArtifactOwner(uuid(rs, "organization_id"), uuid(rs, "workspace_id")),
                new ArtifactRef(uuid(rs, "id")));
    }

    private void lifecycleEvent(ArtifactOwner owner, ArtifactRef reference, String type, String actor, String detail,
            Instant now) {
        jdbc.update("insert into artifact_lifecycle_event(workspace_id, id, artifact_id, event_type, actor, detail, "
                        + "created_at) values (?, ?, ?, ?, ?, ?, ?)",
                owner.workspaceId(), UUID.randomUUID(), reference.value(), type, actor, detail, timestamp(now));
    }

    private Instant databaseNow() {
        OffsetDateTime now = jdbc.queryForObject("select current_timestamp", OffsetDateTime.class);
        if (now == null) throw new ArtifactStoreException(UNAVAILABLE);
        return now.toInstant();
    }

    private boolean ownerExists(ArtifactOwner owner) {
        Integer count = jdbc.queryForObject("select count(*) from workspace where id = ? and organization_id = ?",
                Integer.class, owner.workspaceId(), owner.organizationId());
        return count != null && count == 1;
    }

    private ArtifactMetadata map(ResultSet rs, int row) throws SQLException {
        UUID runId = uuid(rs, "run_id");
        ArtifactRunBinding run = runId == null ? null : new ArtifactRunBinding(runId, uuid(rs, "release_id"),
                uuid(rs, "revision_id"), uuid(rs, "cohort_id"));
        ArtifactProvenance provenance = new ArtifactProvenance(rs.getString("provenance_source"),
                rs.getString("provenance_actor"), run, decodeInputs(rs.getString("input_refs")));
        return new ArtifactMetadata(new ArtifactRef(uuid(rs, "id")),
                new ArtifactOwner(uuid(rs, "organization_id"), uuid(rs, "workspace_id")), rs.getString("media_type"),
                rs.getLong("content_size"), new ArtifactDigest(rs.getString("content_digest")),
                instant(rs, "created_at"), instant(rs, "expires_at"),
                ArtifactClassification.valueOf(rs.getString("classification")), rs.getBoolean("redacted"),
                ArtifactValidation.valueOf(rs.getString("validation_state")), provenance,
                ArtifactLifecycleState.valueOf(rs.getString("lifecycle_state")), rs.getString("validation_reason"),
                instant(rs, "state_changed_at"));
    }

    private boolean isExpired(ArtifactMetadata metadata) {
        return metadata.expiresAt() != null && !metadata.expiresAt().isAfter(clock.instant());
    }

    private Path contentPath(ArtifactOwner owner, ArtifactDigest digest) {
        String hex = digest.value().substring("sha256:".length());
        return objects.resolve(owner.workspaceId().toString()).resolve(hex.substring(0, 2)).resolve(hex.substring(2));
    }

    private static Path initializeDirectory(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        Files.createDirectories(absolute);
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("artifact directory is not a real directory");
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static String encodeInputs(List<ArtifactRef> inputs) {
        return String.join(",", inputs.stream().map(input -> input.value().toString()).toList());
    }

    private static List<ArtifactRef> decodeInputs(String encoded) {
        if (encoded == null || encoded.isEmpty()) return List.of();
        return List.of(encoded.split(",")).stream().map(UUID::fromString).map(ArtifactRef::new).toList();
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The staging directory is never readable; restart reconciliation owns stubborn partial files.
        }
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("artifact validation reason is invalid");
        }
        return reason;
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || actor.length() > 256) {
            throw new IllegalArgumentException("artifact lifecycle actor is invalid");
        }
    }

    private enum Reservation { OWNED, ACTIVE, BUSY }

    private record ContentRow(long size, String state, UUID ownerToken, Instant leaseUntil) {}

    private record ContentKey(ArtifactOwner owner, ArtifactDigest digest) {}

    private record ContentIdentity(ArtifactOwner owner, ArtifactDigest digest, long size) {}

    private record ArtifactKey(ArtifactOwner owner, ArtifactRef reference) {}

    private static final class BoundedChannelInputStream extends InputStream {
        private final SeekableByteChannel channel;
        private long remaining;

        private BoundedChannelInputStream(SeekableByteChannel channel, long remaining) {
            this.channel = channel;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (remaining == 0) return -1;
            int bounded = (int) Math.min(length, remaining);
            int read = channel.read(ByteBuffer.wrap(bytes, offset, bounded));
            if (read == -1) throw new IOException("artifact content ended before its authoritative size");
            remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
