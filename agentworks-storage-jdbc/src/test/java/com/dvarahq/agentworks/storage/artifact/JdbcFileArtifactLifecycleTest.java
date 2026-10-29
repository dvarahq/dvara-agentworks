package com.dvarahq.agentworks.storage.artifact;

import com.dvarahq.agentworks.artifact.ArtifactStoreException;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactLifecycleState;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactPinKind;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactValidation;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class JdbcFileArtifactLifecycleTest {
    @TempDir
    Path root;

    private JdbcTemplate jdbc;
    private JdbcFileArtifactStore store;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/common").load().migrate();
        jdbc = new JdbcTemplate(source);
        store = new JdbcFileArtifactStore(jdbc, new TransactionTemplate(new JdbcTransactionManager(source)), root,
                1024 * 1024, Clock.systemUTC(), new BoundedArtifactContentScanner(), Duration.ofMillis(1),
                Duration.ZERO);
    }

    @Test
    void quarantinedUploadsAreRecordedButNeverDiscoverableOrReadable() {
        ArtifactOwner owner = owner();
        var reference = store.put(write(owner, "application/octet-stream", new byte[]{0x7f, 'E', 'L', 'F'}));

        assertThat(store.metadata(owner, reference).validation()).isEqualTo(ArtifactValidation.QUARANTINED);
        assertThat(store.search(com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest.first(owner, 10)).entries())
                .isEmpty();
        assertThatThrownBy(() -> store.read(ArtifactRead.entire(owner, reference)))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.QUARANTINED));
    }

    @Test
    void aPinPreventsExpiryThenUnpinAllowsAuditedGarbageCollection() throws Exception {
        ArtifactOwner owner = owner();
        var reference = store.put(write(owner, "text/plain", "retained".getBytes()));
        var pin = store.pin(owner, reference, ArtifactPinKind.RETENTION, UUID.randomUUID(), null, "test:admin");

        assertThatThrownBy(() -> store.expire(owner, reference, "test:admin"))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.PINNED));
        store.unpin(owner, reference, pin.id(), "test:admin");
        assertThat(store.expire(owner, reference, "test:admin").lifecycle())
                .isEqualTo(ArtifactLifecycleState.EXPIRED);

        var report = store.maintain(100);
        assertThat(report.metadataDeleted()).isEqualTo(1);
        assertThat(report.contentObjectsRemoved()).isEqualTo(1);
        assertThat(store.metadata(owner, reference).lifecycle()).isEqualTo(ArtifactLifecycleState.DELETED);
        try (var files = Files.walk(root.resolve("objects"))) {
            assertThat(files.filter(Files::isRegularFile)).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from artifact_lifecycle_event", Integer.class)).isEqualTo(5);
    }

    @Test
    void maintenanceQuarantinesCorruptionAndRestartKeepsItsDecision() throws Exception {
        ArtifactOwner owner = owner();
        var reference = store.put(write(owner, "text/plain", "durable".getBytes()));
        Path object;
        try (var files = Files.walk(root.resolve("objects"))) {
            object = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.writeString(object, "corrupt");

        assertThat(store.maintain(100).integrityFailures()).isEqualTo(1);
        JdbcFileArtifactStore restarted = new JdbcFileArtifactStore(jdbc, transactionTemplate(), root,
                1024 * 1024, Clock.systemUTC(), new BoundedArtifactContentScanner(), Duration.ofMillis(1),
                Duration.ZERO);
        assertThat(restarted.metadata(owner, reference).validation()).isEqualTo(ArtifactValidation.QUARANTINED);
        assertThatThrownBy(() -> restarted.read(ArtifactRead.entire(owner, reference)))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.QUARANTINED));
    }

    @Test
    void restartReconciliationRemovesExpiredUploadReservationAndPublishedOrphan() throws Exception {
        ArtifactOwner owner = owner();
        String digest = "sha256:" + "a".repeat(64);
        OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
        jdbc.update("insert into artifact_content_object(workspace_id, organization_id, content_digest, content_size, "
                        + "state, reference_count, owner_token, lease_until, created_at, updated_at) "
                        + "values (?, ?, ?, 1, 'STAGING', 0, ?, ?, ?, ?)", owner.workspaceId(),
                owner.organizationId(), digest, UUID.randomUUID(), old, old, old);
        Path orphan = root.resolve("objects").resolve(owner.workspaceId().toString()).resolve("aa")
                .resolve("a".repeat(62));
        Files.createDirectories(orphan.getParent());
        Files.writeString(orphan, "x");

        assertThat(store.maintain(100).staleUploadsRemoved()).isEqualTo(1);
        assertThat(Files.exists(orphan)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from artifact_content_object where workspace_id = ? "
                + "and content_digest = ?", Integer.class, owner.workspaceId(), digest)).isZero();
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new JdbcTransactionManager(jdbc.getDataSource()));
    }

    private ArtifactOwner owner() {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization,
                "workspace");
        return new ArtifactOwner(organization, workspace);
    }

    private static ArtifactWrite write(ArtifactOwner owner, String mediaType, byte[] value) {
        return new ArtifactWrite(owner, mediaType, value.length, null, null, ArtifactClassification.INTERNAL, false,
                ArtifactProvenance.upload("lifecycle-test"), new ByteArrayInputStream(value));
    }
}
