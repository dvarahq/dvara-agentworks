package com.dvarahq.agentworks.storage;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reference-database immutability, integrity, isolation, and concurrent publication behavior. */
class AgentDefinitionRepositoryPostgresTest extends AgentDefinitionRepositoryContract {
    private static final DataSource source = SharedPostgresTestDatabase.createMigrated("agent_definition").source();

    @Override
    protected DataSource dataSource() {
        return source;
    }

    @Test
    void publishedVersionContentCannotBeUpdatedEvenByDirectSql() {
        UUID definition = publishInA("immutable-agent");

        for (String assignment : List.of("spec_json = '{}'", "spec_digest = 'sha256:" + "0".repeat(64) + "'",
                "version_label = '9.9.9'", "sequence_number = 99", "api_version = 'dvarahq.com/v2'",
                "workspace_id = '" + workspaceB.value() + "'", "created_at = current_timestamp")) {
            assertThatThrownBy(() -> jdbc.update("update agent_version set " + assignment + " where definition_id = ?",
                    definition)).as(assignment).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("immutable");
        }
        assertThat(jdbc.queryForObject("select spec_json from agent_version where definition_id = ?", String.class,
                definition)).isEqualTo(SPEC_JSON);
    }

    @Test
    void publishedVersionsCannotBeDeletedOrTruncated() {
        UUID definition = publishInA("retained-agent");

        assertThatThrownBy(() -> jdbc.update("delete from agent_version where definition_id = ?", definition))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.execute("truncate agent_version cascade"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from agent_version where definition_id = ?", Integer.class,
                definition)).isOne();
    }

    @Test
    void forgedDigestIsRejectedByTheReferenceDatabase() {
        UUID definition = UUID.randomUUID();
        tenants.execute(workspaceA, () -> repository.insertDefinition(definition, "forged-agent", null, Instant.now()));

        assertThatThrownBy(() -> tenants.execute(workspaceA, () -> repository.insertVersion(UUID.randomUUID(),
                definition, 1, "0.1.0", "dvarahq.com/v1alpha1", SPEC_JSON.replace("remote-http", "spring-ai"),
                digest(SPEC_JSON), Instant.now()))).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("digest");
    }

    @Test
    void definitionIdentityAndOwnershipCannotBeRewritten() {
        UUID definition = publishInA("identity-agent");

        for (String assignment : List.of("name = 'renamed'", "workspace_id = '" + workspaceB.value() + "'",
                "created_at = current_timestamp")) {
            assertThatThrownBy(() -> jdbc.update("update agent_definition set " + assignment + " where id = ?",
                    definition)).as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.update("update agent_definition set description = 'metadata only' where id = ?", definition))
                .isOne();
    }

    @Test
    void concurrentPublicationsOfOneLabelYieldExactlyOneVersion() throws Exception {
        UUID definition = UUID.randomUUID();
        tenants.execute(workspaceA, () -> repository.insertDefinition(definition, "raced-agent", null, Instant.now()));

        List<Object> outcomes = race(8, index -> publish(definition, "1.0.0"));

        assertThat(outcomes).filteredOn(outcome -> outcome instanceof JdbcAgentDefinitionRepository.VersionRow).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome instanceof DuplicateKeyException).hasSize(7);
        assertThat(tenants.execute(workspaceA, () -> repository.listVersions(definition))).hasSize(1);
    }

    @Test
    void concurrentPublicationsOfDistinctLabelsReceiveDenseSequences() throws Exception {
        UUID definition = UUID.randomUUID();
        tenants.execute(workspaceA, () -> repository.insertDefinition(definition, "busy-agent", null, Instant.now()));

        List<Object> outcomes = race(8, index -> publish(definition, "1.0." + index));

        assertThat(outcomes).allMatch(outcome -> outcome instanceof JdbcAgentDefinitionRepository.VersionRow);
        assertThat(tenants.execute(workspaceA, () -> repository.listVersions(definition)))
                .extracting(JdbcAgentDefinitionRepository.VersionRow::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    private JdbcAgentDefinitionRepository.VersionRow publish(UUID definition, String label) {
        return tenants.execute(workspaceA, () -> {
            repository.lockDefinition(definition).orElseThrow();
            if (repository.findVersion(definition, label).isPresent()) {
                throw new DuplicateKeyException("label already published");
            }
            return repository.insertVersion(UUID.randomUUID(), definition, repository.nextSequence(definition), label,
                    "dvarahq.com/v1alpha1", SPEC_JSON, digest(SPEC_JSON), Instant.now());
        });
    }

    private static List<Object> race(int workers, java.util.function.IntFunction<Object> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (int index = 0; index < workers; index++) {
                int worker = index;
                Callable<Object> task = () -> {
                    start.await();
                    try {
                        return work.apply(worker);
                    } catch (RuntimeException exception) {
                        return exception;
                    }
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
            return outcomes;
        }
    }
}
