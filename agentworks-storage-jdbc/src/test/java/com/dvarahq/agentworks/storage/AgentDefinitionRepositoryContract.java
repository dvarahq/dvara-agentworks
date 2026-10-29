package com.dvarahq.agentworks.storage;

import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Workspace-scoped lineage behavior that every supported database must satisfy. */
abstract class AgentDefinitionRepositoryContract {
    static final String SPEC_JSON = "{\"apiVersion\":\"dvarahq.com/v1alpha1\",\"kind\":\"Agent\","
            + "\"metadata\":{\"name\":\"refund-agent\",\"version\":\"0.1.0\"},\"spec\":{\"executor\":{\"type\":\"remote-http\"}}}";

    protected JdbcTemplate jdbc;
    protected TenantTransactionTemplate tenants;
    protected JdbcAgentDefinitionRepository repository;
    protected WorkspaceId workspaceA;
    protected WorkspaceId workspaceB;

    protected abstract DataSource dataSource();

    @BeforeEach
    void setUpWorkspaces() {
        DataSource source = dataSource();
        jdbc = new JdbcTemplate(source);
        TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        tenants = new TenantTransactionTemplate(transactions);
        repository = new JdbcAgentDefinitionRepository(jdbc);
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        workspaceA = new WorkspaceId(UUID.randomUUID());
        workspaceB = new WorkspaceId(UUID.randomUUID());
        jdbc.update("insert into organization(id, name) values (?, ?)", organizationA, "org-a-" + organizationA);
        jdbc.update("insert into organization(id, name) values (?, ?)", organizationB, "org-b-" + organizationB);
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspaceA.value(), organizationA, "a");
        jdbc.update("insert into workspace(id, organization_id, name) values (?, ?, ?)", workspaceB.value(), organizationB, "b");
    }

    @Test
    void lineageRoundTripsWithinItsWorkspace() {
        UUID definition = UUID.randomUUID();
        Instant created = Instant.parse("2026-09-22T10:15:30.123456789Z");
        tenants.execute(workspaceA, () -> {
            repository.insertDefinition(definition, "refund-agent", "Refunds", created);
            repository.insertVersion(UUID.randomUUID(), definition, 1, "0.1.0", "dvarahq.com/v1alpha1", SPEC_JSON,
                    digest(SPEC_JSON), created);
            return repository.insertVersion(UUID.randomUUID(), definition, 2, "0.2.0", "dvarahq.com/v1alpha1",
                    SPEC_JSON, digest(SPEC_JSON), created);
        });

        var read = tenants.execute(workspaceA, () -> repository.findDefinition(definition)).orElseThrow();
        assertThat(read.workspaceId()).isEqualTo(workspaceA.value());
        assertThat(read.createdAt()).isEqualTo(Instant.parse("2026-09-22T10:15:30.123456Z"));
        assertThat(tenants.execute(workspaceA, () -> repository.listVersions(definition)))
                .extracting(JdbcAgentDefinitionRepository.VersionRow::versionLabel).containsExactly("0.1.0", "0.2.0");
        assertThat(tenants.execute(workspaceA, () -> repository.findVersion(definition, "0.1.0")).orElseThrow().specJson())
                .isEqualTo(SPEC_JSON);
        assertThat(tenants.execute(workspaceA, () -> repository.nextSequence(definition))).isEqualTo(3);
    }

    @Test
    void anotherWorkspaceCannotReadDefinitionsOrVersions() {
        UUID definition = publishInA("refund-agent");

        tenants.executeWithoutResult(workspaceB, () -> {
            assertThat(repository.findDefinition(definition)).isEmpty();
            assertThat(repository.lockDefinition(definition)).isEmpty();
            assertThat(repository.findDefinitionByName("refund-agent")).isEmpty();
            assertThat(repository.listDefinitions()).isEmpty();
            assertThat(repository.findVersion(definition, "0.1.0")).isEmpty();
            assertThat(repository.listVersions(definition)).isEmpty();
            assertThat(repository.nextSequence(definition)).isOne();
        });
    }

    @Test
    void anotherWorkspaceCannotWriteIntoALineage() {
        UUID definition = publishInA("refund-agent");

        assertThatThrownBy(() -> tenants.execute(workspaceB, () -> repository.insertVersion(UUID.randomUUID(),
                definition, 2, "0.2.0", "dvarahq.com/v1alpha1", SPEC_JSON, digest(SPEC_JSON), Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(tenants.execute(workspaceA, () -> repository.listVersions(definition))).hasSize(1);
    }

    @Test
    void definitionNamesAreUniquePerWorkspaceOnly() {
        publishInA("refund-agent");

        assertThatThrownBy(() -> tenants.execute(workspaceA, () -> repository.insertDefinition(UUID.randomUUID(),
                "refund-agent", null, Instant.now()))).isInstanceOf(DuplicateKeyException.class);
        tenants.execute(workspaceB, () -> repository.insertDefinition(UUID.randomUUID(), "refund-agent", null,
                Instant.now()));
    }

    @Test
    void versionLabelsAndSequencesAreUniqueWithinALineage() {
        UUID definition = publishInA("refund-agent");

        assertThatThrownBy(() -> tenants.execute(workspaceA, () -> repository.insertVersion(UUID.randomUUID(),
                definition, 2, "0.1.0", "dvarahq.com/v1alpha1", SPEC_JSON, digest(SPEC_JSON), Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> tenants.execute(workspaceA, () -> repository.insertVersion(UUID.randomUUID(),
                definition, 1, "0.9.0", "dvarahq.com/v1alpha1", SPEC_JSON, digest(SPEC_JSON), Instant.now())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void malformedDigestIsRejected() {
        UUID definition = UUID.randomUUID();
        tenants.execute(workspaceA, () -> repository.insertDefinition(definition, "refund-agent", null, Instant.now()));

        assertThatThrownBy(() -> tenants.execute(workspaceA, () -> repository.insertVersion(UUID.randomUUID(),
                definition, 1, "0.1.0", "dvarahq.com/v1alpha1", SPEC_JSON, "md5:abc", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repositoryFailsClosedWithoutWorkspaceBinding() {
        assertThatThrownBy(() -> repository.listDefinitions()).isInstanceOf(MissingTenantBindingException.class);
        assertThatThrownBy(() -> repository.insertDefinition(UUID.randomUUID(), "x", null, Instant.now()))
                .isInstanceOf(MissingTenantBindingException.class);
    }

    protected UUID publishInA(String name) {
        UUID definition = UUID.randomUUID();
        tenants.execute(workspaceA, () -> {
            repository.insertDefinition(definition, name, null, Instant.now());
            return repository.insertVersion(UUID.randomUUID(), definition, 1, "0.1.0", "dvarahq.com/v1alpha1", SPEC_JSON,
                    digest(SPEC_JSON), Instant.now());
        });
        return definition;
    }

    static String digest(String json) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
