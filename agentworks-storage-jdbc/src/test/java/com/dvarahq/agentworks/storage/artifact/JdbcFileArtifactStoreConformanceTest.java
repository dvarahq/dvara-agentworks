package com.dvarahq.agentworks.storage.artifact;

import com.dvarahq.agentworks.artifact.ArtifactStore;
import com.dvarahq.agentworks.artifact.conformance.ArtifactStoreConformanceHarness;
import com.dvarahq.agentworks.artifact.conformance.ArtifactStoreConformanceSuite;
import com.dvarahq.agentworks.domain.artifact.ArtifactOwner;
import com.dvarahq.agentworks.domain.artifact.ArtifactContent;
import com.dvarahq.agentworks.domain.artifact.ArtifactRead;
import com.dvarahq.agentworks.domain.artifact.ArtifactRef;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchPage;
import com.dvarahq.agentworks.domain.artifact.ArtifactSearchRequest;
import com.dvarahq.agentworks.domain.artifact.ArtifactWrite;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import javax.sql.DataSource;

abstract class JdbcFileArtifactStoreConformanceTest extends ArtifactStoreConformanceSuite {
    @TempDir
    Path contentRoot;

    @Override
    protected ArtifactStoreConformanceHarness createHarness() {
        DataSource source = dataSource();
        Flyway.configure().dataSource(source).locations(migrationLocations()).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        MutableClock clock = new MutableClock(Instant.parse("2026-10-09T12:00:00Z"));
        TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        ArtifactStore backend = new JdbcFileArtifactStore(jdbc, transactions, contentRoot, 1024 * 1024, clock);
        ArtifactStore store = new ArtifactStore() {
            @Override
            public java.util.Set<com.dvarahq.agentworks.artifact.ArtifactStoreCapability> capabilities() {
                return backend.capabilities();
            }

            @Override
            public ArtifactRef put(ArtifactWrite request) {
                ensureOwner(jdbc, request.owner());
                return backend.put(request);
            }

            @Override
            public ArtifactContent read(ArtifactRead request) {
                ensureOwner(jdbc, request.owner());
                return backend.read(request);
            }

            @Override
            public ArtifactSearchPage search(ArtifactSearchRequest request) {
                ensureOwner(jdbc, request.owner());
                return backend.search(request);
            }

            @Override
            public void close() {
                backend.close();
            }
        };
        return new ArtifactStoreConformanceHarness() {
            @Override
            public ArtifactStore store() {
                return store;
            }

            @Override
            public void advance(Duration duration) {
                clock.advance(duration);
            }

            @Override
            public void quarantine(ArtifactOwner owner, ArtifactRef reference) {
                jdbc.update("update artifact_object set validation_state = 'QUARANTINED' "
                                + "where workspace_id = ? and organization_id = ? and id = ?", owner.workspaceId(),
                        owner.organizationId(), reference.value());
            }

            @Override
            public long publishedCount(ArtifactOwner owner) {
                Long count = jdbc.queryForObject("select count(*) from artifact_object where workspace_id = ? "
                                + "and organization_id = ?", Long.class, owner.workspaceId(), owner.organizationId());
                return count == null ? 0 : count;
            }
        };
    }

    protected abstract DataSource dataSource();

    protected String[] migrationLocations() {
        return new String[]{"classpath:db/migration/common"};
    }

    private static void ensureOwner(JdbcTemplate jdbc, ArtifactOwner owner) {
        jdbc.update("insert into organization(id, name) select ?, ? where not exists "
                        + "(select 1 from organization where id = ?)", owner.organizationId(),
                "tck-org-" + owner.organizationId(), owner.organizationId());
        jdbc.update("insert into workspace(id, organization_id, name) select ?, ?, ? where not exists "
                        + "(select 1 from workspace where id = ?)", owner.workspaceId(), owner.organizationId(),
                "tck-workspace-" + owner.workspaceId(), owner.workspaceId());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!getZone().equals(zone)) throw new UnsupportedOperationException("test clock is UTC");
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}

final class JdbcFileArtifactStoreH2Test extends JdbcFileArtifactStoreConformanceTest {
    @Override
    protected DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
