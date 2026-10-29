package com.dvarahq.agentworks.storage.evaluation;

import com.dvarahq.agentworks.evaluation.CustomEvaluator;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Budget;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Case;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Observation;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.Outcome;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.RunStatus;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.StartRequest;
import com.dvarahq.agentworks.evaluation.EvaluationContracts.EvaluatorType;
import com.dvarahq.agentworks.evaluation.EvaluationDigests;
import com.dvarahq.agentworks.evaluation.EvaluationService;
import com.dvarahq.agentworks.evaluation.EvaluatorEngine;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcEvaluationRepositoryH2Test {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private UUID workspaceA;
    private UUID workspaceB;
    private JdbcTemplate jdbc;
    private JdbcEvaluationRepository repository;

    @BeforeEach void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/common").load().migrate();
        jdbc = new JdbcTemplate(source);
        var tenants = new TenantTransactionTemplate(new TransactionTemplate(new JdbcTransactionManager(source)));
        repository = new JdbcEvaluationRepository(jdbc, tenants);
        workspaceA = workspace(jdbc, "a"); workspaceB = workspace(jdbc, "b");
    }

    @Test void persistsDeterministicReportIdempotentlyAndComparesBaseline() {
        EvaluationService service = service(List.of());
        var dataset = service.createDataset(workspaceA, "quality", 1, false, null,
                List.of(new Case("one", json("{\"q\":1}"), json("{\"answer\":42}"), Map.of("suite", "golden"))));
        var exact = service.createEvaluator(workspaceA, "exact", 1, EvaluatorType.EXACT, json("{}"));
        Observation observation = new Observation("one", json("{\"answer\":42}"), List.of(), true, 10,
                new BigDecimal("0.02"), "run:source", EvaluationDigests.text("source"));
        StartRequest request = new StartRequest(dataset.id(), List.of(exact.id()), "release:1", "evidence:pending",
                Map.of("one", observation), new Budget(new BigDecimal("1"), 100, Duration.ofSeconds(5), 1), "key-1");

        var first = service.start(workspaceA, request); var repeated = service.start(workspaceA, request);
        assertThat(first.run().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(first.run().passedResults()).isOne();
        assertThat(repeated.run().id()).isEqualTo(first.run().id());
        assertThat(repeated.digest()).isEqualTo(first.digest());
        var baseline = service.createBaseline(workspaceA, "main", 1, first.run().id(),
                Map.of("minimumPassRate", new BigDecimal("0.9"), "maximumPassRateDrop", new BigDecimal("0.05")),
                Map.of("model", "fake-v1"));
        assertThat(service.compare(workspaceA, baseline.id(), first.run().id()).regression()).isFalse();
        assertThat(service.datasets(workspaceB)).isEmpty();
        assertThatThrownBy(() -> service.dataset(workspaceB, dataset.id())).hasMessageContaining("not found");
    }

    @Test void enforcesBudgetsAndResumesOnlyMissingResultsAfterEvaluatorFailure() {
        AtomicBoolean fail = new AtomicBoolean(true);
        CustomEvaluator flaky = new CustomEvaluator() {
            @Override public String identity() { return "test.flaky.v1"; }
            @Override public Outcome evaluate(Case item, Observation observation, JsonNode config) {
                if (item.id().equals("two") && fail.getAndSet(false)) throw new IllegalStateException("interrupted");
                return new Outcome(true, BigDecimal.ONE, "PASS", json("{\"stable\":true}"));
            }
        };
        EvaluationService service = service(List.of(flaky));
        var dataset = service.createDataset(workspaceA, "restart", 1, false, null, List.of(
                new Case("one", json("1"), json("1"), Map.of()), new Case("two", json("2"), json("2"), Map.of())));
        var evaluator = service.createEvaluator(workspaceA, "custom", 1, EvaluatorType.CUSTOM_JAVA,
                json("{\"identity\":\"test.flaky.v1\"}"));
        Map<String, Observation> observations = Map.of(
                "one", observation("one", "1", "0.01"), "two", observation("two", "2", "0.01"));
        StartRequest request = new StartRequest(dataset.id(), List.of(evaluator.id()), "candidate", "", observations,
                new Budget(BigDecimal.ONE, 100, Duration.ofSeconds(5), 1), "restart-key");

        assertThatThrownBy(() -> service.start(workspaceA, request)).isInstanceOf(IllegalStateException.class);
        UUID runId = jdbc.queryForObject("select id from evaluation_run where workspace_id=? and idempotency_key='restart-key'",
                UUID.class, workspaceA);
        assertThat(repository.results(workspaceA, runId)).hasSize(1);
        assertThat(service.resume(workspaceA, runId).run().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(repository.results(workspaceA, runId)).hasSize(2);

        var costly = service.createDataset(workspaceA, "cost", 1, false, null,
                List.of(new Case("cost", json("1"), json("1"), Map.of())));
        var exact = service.createEvaluator(workspaceA, "exact-cost", 1, EvaluatorType.EXACT, json("{}"));
        var report = service.start(workspaceA, new StartRequest(costly.id(), List.of(exact.id()), "candidate", "",
                Map.of("cost", observation("cost", "1", "2.00")), new Budget(new BigDecimal("1.00"), 0, Duration.ZERO, 1), "cost-key"));
        assertThat(report.run().status()).isEqualTo(RunStatus.BUDGET_EXCEEDED);
        assertThat(report.results()).isEmpty();
    }

    @Test void cancellationStopsAtACellBoundaryAndResumeCompletesOnlyMissingCells() {
        AtomicBoolean cancelOnce = new AtomicBoolean(true);
        CustomEvaluator cancelling = new CustomEvaluator() {
            @Override public String identity() { return "test.cancel.v1"; }
            @Override public Outcome evaluate(Case item, Observation observation, JsonNode config) {
                if (cancelOnce.getAndSet(false)) {
                    UUID runId = jdbc.queryForObject("select id from evaluation_run where workspace_id=? and idempotency_key='cancel-key'",
                            UUID.class, workspaceA);
                    repository.requestCancellation(workspaceA, runId);
                }
                return new Outcome(true, BigDecimal.ONE, "PASS", json("{\"stable\":true}"));
            }
        };
        EvaluationService service = service(List.of(cancelling));
        var dataset = service.createDataset(workspaceA, "cancel", 1, false, null, List.of(
                new Case("one", json("1"), json("1"), Map.of()), new Case("two", json("2"), json("2"), Map.of())));
        var evaluator = service.createEvaluator(workspaceA, "cancel", 1, EvaluatorType.CUSTOM_JAVA,
                json("{\"identity\":\"test.cancel.v1\"}"));
        StartRequest request = new StartRequest(dataset.id(), List.of(evaluator.id()), "candidate", "",
                Map.of("one", observation("one", "1", "0.01"), "two", observation("two", "2", "0.01")),
                new Budget(BigDecimal.ONE, 100, Duration.ofSeconds(5), 1), "cancel-key");

        var cancelled = service.start(workspaceA, request);
        assertThat(cancelled.run().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(cancelled.results()).hasSize(1);
        var resumed = service.resume(workspaceA, cancelled.run().id());
        assertThat(resumed.run().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.results()).hasSize(2);
    }

    @Test void sensitiveFixturesRequireBoundedRetentionAndMalformedDatasetsFailBeforePersistence() {
        EvaluationService service = service(List.of());
        List<Case> cases = List.of(new Case("one", json("1"), json("1"), Map.of()));
        assertThatThrownBy(() -> service.createDataset(workspaceA, "sensitive", 1, true, null, cases))
                .isInstanceOf(com.dvarahq.agentworks.evaluation.EvaluationException.class)
                .extracting("code").isEqualTo("RETENTION_INVALID");
        assertThatThrownBy(() -> service.createDataset(workspaceA, "sensitive", 1, true,
                NOW.plus(Duration.ofDays(31)), cases))
                .isInstanceOf(com.dvarahq.agentworks.evaluation.EvaluationException.class)
                .extracting("code").isEqualTo("RETENTION_INVALID");
        assertThatThrownBy(() -> service.createDataset(workspaceA, "duplicates", 1, false, null,
                List.of(cases.getFirst(), cases.getFirst())))
                .isInstanceOf(com.dvarahq.agentworks.evaluation.EvaluationException.class)
                .extracting("code").isEqualTo("DUPLICATE_CASE");
        assertThat(jdbc.queryForObject("select count(*) from evaluation_dataset where workspace_id=?", Integer.class, workspaceA))
                .isZero();
        var sensitive = service.createDataset(workspaceA, "sensitive", 1, true, NOW.plus(Duration.ofDays(1)), cases);
        String originalInputDigest = EvaluationDigests.json(sensitive.cases().getFirst().input());
        assertThat(repository.purgeExpiredSensitive(workspaceA, NOW.plus(Duration.ofDays(2)))).isOne();
        var purged = service.dataset(workspaceA, sensitive.id());
        assertThat(purged.cases().getFirst().input().path("$agentworksPurgedDigest").stringValue())
                .isEqualTo(originalInputDigest);
        assertThat(purged.cases().getFirst().expected().path("$agentworksPurgedDigest").isString()).isTrue();
        assertThat(repository.purgeExpiredSensitive(workspaceA, NOW.plus(Duration.ofDays(2)))).isZero();
    }

    private EvaluationService service(List<CustomEvaluator> custom) { return new EvaluationService(repository, new EvaluatorEngine(custom), Clock.fixed(NOW, ZoneOffset.UTC)); }
    private static Observation observation(String id, String output, String cost) { return new Observation(id, json(output), List.of(), true, 10, new BigDecimal(cost), "source:" + id, EvaluationDigests.text(id)); }
    private static JsonNode json(String value) { return JSON.readTree(value); }
    private static UUID workspace(JdbcTemplate jdbc, String name) {
        UUID organization = UUID.randomUUID(); UUID workspace = UUID.randomUUID();
        jdbc.update("insert into organization(id,name) values (?,?)", organization, "org-" + name);
        jdbc.update("insert into workspace(id,organization_id,name) values (?,?,?)", workspace, organization, name);
        return workspace;
    }
}
