package com.dvarahq.agentworks.storage.artifact;

import com.dvarahq.agentworks.domain.artifact.ArtifactClassification;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactProvenance;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import com.dvarahq.agentworks.storage.SharedPostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class JdbcFileArtifactStorePostgresTest extends JdbcFileArtifactStoreConformanceTest {
    private static final DataSource source = SharedPostgresTestDatabase.createMigrated("artifact_store").source();

    @Override
    protected DataSource dataSource() {
        return source;
    }

    @Override
    protected String[] migrationLocations() {
        return new String[]{"classpath:db/migration/common", "classpath:db/vendor/postgresql"};
    }

    @Test
    void postgresRejectsImmutableRewritesAndLifecycleResurrectionBelowTheStore() {
        DataSource source = dataSource();
        Flyway.configure().dataSource(source).locations(migrationLocations()).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        UUID organization = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id, name) values (?, ?)", organization, "org-" + organization);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspace, organization,
                "workspace");
        ArtifactOwner owner = new ArtifactOwner(organization, workspace);
        var store = new JdbcFileArtifactStore(jdbc,
                new TransactionTemplate(new JdbcTransactionManager(source)), contentRoot, 1024);
        byte[] bytes = "authority".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var reference = store.put(new ArtifactWrite(owner, "text/plain", bytes.length, null, null,
                ArtifactClassification.INTERNAL, false, ArtifactProvenance.upload("postgres-test"),
                new ByteArrayInputStream(bytes)));

        assertThatThrownBy(() -> jdbc.update("update artifact_object set media_type = 'text/html' "
                        + "where workspace_id = ? and id = ?", workspace, reference.value()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("IMMUTABLE_ARTIFACT");
        store.expire(owner, reference, "postgres-test");
        assertThatThrownBy(() -> jdbc.update("update artifact_object set lifecycle_state = 'ACTIVE', "
                        + "expired_at = null where workspace_id = ? and id = ?", workspace, reference.value()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ARTIFACT_RESURRECTION");
    }
}
