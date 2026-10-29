package com.dvarahq.agentworks.storage.artifact;

import com.dvarahq.agentworks.artifact.ArtifactStoreException;
import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class JdbcFileArtifactStoreSecurityTest {
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
        store = new JdbcFileArtifactStore(jdbc,
                new TransactionTemplate(new JdbcTransactionManager(source)), root, 5);
    }

    @Test
    void oversizedAndTruncatedUploadsNeverPublishMetadataOrPartials() throws Exception {
        ArtifactOwner owner = owner();
        assertThatThrownBy(() -> store.put(write(owner, 6, "123456")))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.LIMIT_EXCEEDED));
        assertThatThrownBy(() -> store.put(write(owner, 5, "1234")))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.INTEGRITY_FAILED));

        assertThat(jdbc.queryForObject("select count(*) from artifact_object", Long.class)).isZero();
        try (var files = Files.list(root.resolve("staging"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void identicalContentUsesSeparateTenantNamespacesAndMetadata() throws Exception {
        ArtifactOwner first = owner();
        ArtifactOwner second = owner();
        store.put(write(first, 5, "12345"));
        store.put(write(second, 5, "12345"));

        assertThat(jdbc.queryForObject("select count(*) from artifact_object", Long.class)).isEqualTo(2);
        try (var files = Files.walk(root.resolve("objects"))) {
            assertThat(files.filter(Files::isRegularFile)).hasSize(2);
        }
    }

    @Test
    void corruptedDigestObjectIsNeverReturned() throws Exception {
        ArtifactOwner owner = owner();
        var reference = store.put(write(owner, 5, "12345"));
        Path object;
        try (var files = Files.walk(root.resolve("objects"))) {
            object = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.writeString(object, "54321");

        assertThatThrownBy(() -> store.read(ArtifactRead.entire(owner, reference)))
                .isInstanceOfSatisfying(ArtifactStoreException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(ArtifactStoreException.Reason.UNAVAILABLE));
    }

    private ArtifactOwner owner() {
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization,
                "workspace");
        return new ArtifactOwner(organization, workspace);
    }

    private static ArtifactWrite write(ArtifactOwner owner, long declared, String value) {
        return new ArtifactWrite(owner, "text/plain", declared, null, null, ArtifactClassification.INTERNAL, false,
                ArtifactProvenance.upload("security-test"), new ByteArrayInputStream(value.getBytes()));
    }
}
